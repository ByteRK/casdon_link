package com.example.casdonlink.profile

/** 规则匹配与优先级选择。 */
object ProfileMatcher {
    data class Selection(val selected: DeviceProfile?, val matched: List<DeviceProfile>)

    fun select(profiles: List<DeviceProfile>, checks: Map<FileCheck, Boolean>): Selection {
        val matched = profiles.filter { profile ->
            profile.match.all.all { checks[it] == true } &&
                profile.match.none.none { checks[it] == true }
        }
        val sorted = matched.sortedWith(
            compareByDescending<DeviceProfile> { it.priority }.thenBy { it.id }
        )
        return Selection(selected = sorted.firstOrNull(), matched = sorted)
    }
}
