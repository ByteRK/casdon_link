import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 修复包加密工具：把目录或 ZIP 打成 .clpkg，或把 .clpkg 解成 ZIP/目录。
 *
 * 用法：
 *   java PackageTool.java pack --source <目录或 zip> [--output <file>] [--iterations 210000] [--force]
 *   java PackageTool.java unpack --input <file.clpkg> [--output-dir <dir> | --output-zip <file>] [--force]
 *
 * 密码优先取命令行 --password，其次取环境变量 CASDON_PKG_PASSWORD；都没有时交互输入。
 * 容器格式与 Android 端 PackageCrypto.kt 保持一致，详见 docs/PACKAGE_FORMAT.md。
 */
public final class PackageTool {
    private static final byte[] MAGIC = "CLPKG".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int DEFAULT_ITERATIONS = 210_000;
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int HEADER_BYTES = 5 + 1 + 4 + SALT_BYTES + NONCE_BYTES;
    private static final long MAX_PLAIN_BYTES = 256L * 1024 * 1024;

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                usage();
                System.exit(2);
            }
            String command = args[0];
            Map<String, String> options = parseOptions(args);
            if (!command.equals("pack") && !command.equals("unpack")) {
                usage();
                System.exit(2);
            }
            char[] password = readPassword(options.get("--password"));
            try {
                if (command.equals("pack")) {
                    pack(options, password);
                } else {
                    unpack(options, password);
                }
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (Exception error) {
            System.err.println("失败: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            System.exit(1);
        }
    }

    private static void pack(Map<String, String> options, char[] password) throws Exception {
        String sourceText = require(options, "--source");
        Path source = Paths.get(sourceText).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new IOException("源路径不存在: " + source);
        }
        Path output = options.containsKey("--output")
                ? Paths.get(options.get("--output")).toAbsolutePath().normalize()
                : defaultPackOutput(source);
        if (Files.exists(output) && !options.containsKey("--force")) {
            throw new IOException("输出已存在，使用 --force 覆盖: " + output);
        }

        byte[] plain;
        if (Files.isDirectory(source)) {
            if (!Files.isRegularFile(source.resolve("manifest.json"))) {
                throw new IOException("源目录根目录缺少 manifest.json");
            }
            plain = zipDirectory(source, output);
        } else {
            plain = Files.readAllBytes(source);
            validateZip(plain);
        }
        if (plain.length > MAX_PLAIN_BYTES) {
            throw new IOException("明文超过 256 MB 上限");
        }
        int iterations = iterations(options);
        byte[] container = encrypt(plain, password, iterations);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, container);
        System.out.println("已生成: " + output);
        System.out.println("明文 " + plain.length + " 字节 -> 加密包 " + container.length + " 字节");
        System.out.println("SHA-256: " + sha256(container));
        System.out.println("清单: " + manifestSummary(plain));
    }

    private static void unpack(Map<String, String> options, char[] password) throws Exception {
        Path input = Paths.get(require(options, "--input")).toAbsolutePath().normalize();
        boolean toZip = options.containsKey("--output-zip");
        boolean toDir = options.containsKey("--output-dir");
        if (toZip && toDir) {
            throw new IOException("--output-zip 与 --output-dir 只能二选一");
        }
        byte[] container = Files.readAllBytes(input);
        byte[] plain = decrypt(container, password);
        validateZip(plain);
        if (toZip) {
            Path output = Paths.get(options.get("--output-zip")).toAbsolutePath().normalize();
            if (Files.exists(output) && !options.containsKey("--force")) {
                throw new IOException("输出已存在，使用 --force 覆盖: " + output);
            }
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(output, plain);
            System.out.println("已还原 ZIP: " + output);
        } else {
            Path directory = toDir
                    ? Paths.get(options.get("--output-dir")).toAbsolutePath().normalize()
                    : defaultUnpackDirectory(input);
            extractZip(plain, directory, options.containsKey("--force"));
            System.out.println("已解包到: " + directory);
        }
        System.out.println("清单: " + manifestSummary(plain));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 1; index < args.length; index++) {
            String key = args[index];
            if (!key.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + key);
            }
            if (key.equals("--force")) {
                options.put(key, "true");
                continue;
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("参数缺少值: " + key);
            }
            options.put(key, args[++index]);
        }
        return options;
    }

    private static int iterations(Map<String, String> options) {
        int iterations = options.containsKey("--iterations")
                ? Integer.parseInt(options.get("--iterations"))
                : DEFAULT_ITERATIONS;
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("迭代次数需在 " + MIN_ITERATIONS + ".." + MAX_ITERATIONS + " 之间");
        }
        return iterations;
    }

    private static char[] readPassword(String argument) {
        if (argument != null && !argument.isEmpty()) {
            return argument.toCharArray();
        }
        String environment = System.getenv("CASDON_PKG_PASSWORD");
        if (environment != null && !environment.isEmpty()) {
            return environment.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("请设置 CASDON_PKG_PASSWORD，或通过包装脚本交互输入密码");
        }
        char[] password = console.readPassword("修复包密码: ");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return password;
    }

    private static byte[] encrypt(byte[] plain, char[] password, int iterations) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] header = new byte[HEADER_BYTES];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = (byte) VERSION;
        ByteBuffer.wrap(header, MAGIC.length + 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(iterations);
        System.arraycopy(salt, 0, header, MAGIC.length + 1 + 4, salt.length);
        System.arraycopy(nonce, 0, header, MAGIC.length + 1 + 4 + SALT_BYTES, nonce.length);

        byte[] key = pbkdf2(password, salt, iterations);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            ciphertext = cipher.doFinal(plain);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        byte[] container = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, container, 0, header.length);
        System.arraycopy(ciphertext, 0, container, header.length, ciphertext.length);
        return container;
    }

    private static byte[] decrypt(byte[] container, char[] password) throws Exception {
        if (container.length < HEADER_BYTES + TAG_BYTES) {
            throw new IOException("不是有效的加密修复包");
        }
        if (!Arrays.equals(Arrays.copyOfRange(container, 0, MAGIC.length), MAGIC)) {
            throw new IOException("不是有效的加密修复包（缺少 CLPKG 标识）");
        }
        if ((container[MAGIC.length] & 0xff) != VERSION) {
            throw new IOException("不支持的修复包版本");
        }
        int iterations = ByteBuffer.wrap(container, MAGIC.length + 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IOException("修复包头部参数无效");
        }
        int saltOffset = MAGIC.length + 1 + 4;
        int nonceOffset = saltOffset + SALT_BYTES;
        byte[] header = Arrays.copyOfRange(container, 0, HEADER_BYTES);
        byte[] salt = Arrays.copyOfRange(container, saltOffset, saltOffset + SALT_BYTES);
        byte[] nonce = Arrays.copyOfRange(container, nonceOffset, nonceOffset + NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(container, HEADER_BYTES, container.length);
        byte[] key = pbkdf2(password, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            try {
                return cipher.doFinal(ciphertext);
            } catch (javax.crypto.AEADBadTagException error) {
                throw new IOException("密码错误或修复包已损坏");
            }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] zipDirectory(Path root, Path exclude) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.toAbsolutePath().normalize().equals(exclude))
                    .sorted()
                    .collect(Collectors.toList());
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                String name = root.relativize(file).toString().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IOException("源目录包含不安全路径: " + name);
                }
                total += Files.size(file);
                if (total > MAX_PLAIN_BYTES) {
                    throw new IOException("源目录超过 256 MB 上限");
                }
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static void validateZip(byte[] zipBytes) throws IOException {
        Set<String> names = new HashSet<>();
        boolean manifest = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IOException("ZIP 包含不安全路径: " + name);
                }
                if (!names.add(name)) {
                    throw new IOException("ZIP 包含重复路径: " + name);
                }
                if (name.equals("manifest.json")) {
                    manifest = true;
                }
            }
        }
        if (!manifest) {
            throw new IOException("ZIP 根目录缺少 manifest.json");
        }
    }

    private static void extractZip(byte[] zipBytes, Path directory, boolean force) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> entries = Files.list(directory)) {
                if (!force && entries.findAny().isPresent()) {
                    throw new IOException("输出目录非空，使用 --force 覆盖: " + directory);
                }
            }
        } else {
            Files.createDirectories(directory);
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory)) {
                    throw new IOException("ZIP 路径越界: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static String manifestSummary(byte[] zipBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals("manifest.json")) {
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) > 0 && buffer.size() < 64 * 1024) {
                    buffer.write(chunk, 0, read);
                }
                String text = buffer.toString(StandardCharsets.UTF_8);
                String name = firstGroup("\"name\"\\s*:\\s*\"([^\"]*)\"", text);
                String version = firstGroup("\"version\"\\s*:\\s*\"([^\"]*)\"", text);
                String packageId = firstGroup("\"packageId\"\\s*:\\s*\"([^\"]*)\"", text);
                return (name == null ? "manifest.json" : name)
                        + (version == null ? "" : " " + version)
                        + (packageId == null ? "" : " (" + packageId + ")");
            }
        } catch (Exception ignored) {
            // 摘要失败不影响打包/解包结果。
        }
        return "manifest.json";
    }

    private static String firstGroup(String regex, String text) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : digest) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static Path defaultPackOutput(Path source) {
        String name = source.getFileName().toString();
        if (name.toLowerCase().endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        Path parent = source.getParent();
        return (parent == null ? Paths.get(".") : parent).resolve(name + ".clpkg").toAbsolutePath().normalize();
    }

    private static Path defaultUnpackDirectory(Path input) {
        String name = input.getFileName().toString();
        if (name.toLowerCase().endsWith(".clpkg")) {
            name = name.substring(0, name.length() - 6);
        }
        Path parent = input.getParent();
        return (parent == null ? Paths.get(".") : parent).resolve(name + "_unpacked").toAbsolutePath().normalize();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("缺少参数 " + key);
        }
        return value;
    }

    private static void usage() {
        System.out.println("用法:");
        System.out.println("  java PackageTool.java pack --source <目录或 zip> [--output <file.clpkg>] [--iterations 210000] [--force]");
        System.out.println("  java PackageTool.java unpack --input <file.clpkg> [--output-dir <dir> | --output-zip <file.zip>] [--force]");
        System.out.println("密码: --password 或环境变量 CASDON_PKG_PASSWORD；未提供时交互输入。");
    }
}
