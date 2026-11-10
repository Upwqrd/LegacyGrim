`FasterCrystals-104d743-api.jar` contains only the unmodified `FastCrystalPlaceEvent.class`
and LICENSE from upstream [mcpvp-club/FasterCrystals, commit 104d743312904f277a509889d0fc9d1f20bb4fda](https://github.com/mcpvp-club/FasterCrystals/tree/104d743312904f277a509889d0fc9d1f20bb4fda),
built with Java 21 using `./gradlew shadowJar`. This compile/test dependency is not bundled in LegacyGrim.

The upstream event has no hand field. Identical stacks in both hands use main-hand rules to preserve the swap limit.
