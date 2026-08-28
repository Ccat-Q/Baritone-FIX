/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.utils;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockOptionalMetaTest {

    /**
     * Regression test for the #mine drops cache: a failed drop computation must
     * not be cached as an empty drop list. {@code BlockOptionalMeta.drops}
     * previously used {@code computeIfAbsent}, so when the lazy registry lookup
     * failed (no live Level registry access) the empty result was cached forever
     * and a later in-world {@code #mine} could never retry and recover the real
     * drops.
     *
     * <p>This is the plain-JVM-safe seam for that policy: {@link
     * BlockOptionalMeta#cacheOrCompute} is a package-private pure helper that
     * both caches successful computations and skips caching failures, and it is
     * exercised here with plain Java objects. The test deliberately does NOT
     * initialize any Minecraft runtime class (ServerLevelStub, Minecraft, Block,
     * registries) -- those cannot be initialized in this headless JUnit
     * environment -- and it does not inspect source text: it asserts the actual
     * cache behavior the in-game flow depends on.
     */
    @Test
    public void failedDropComputationIsNotCached() {
        Map<String, String> cache = new HashMap<>();

        // Failed computation (null result, exactly what computeDrops returns when
        // registry access is unavailable): the fallback is returned and NOT cached.
        assertEquals(
            "fallback",
            BlockOptionalMeta.cacheOrCompute(cache, "block", key -> null, "fallback")
        );
        assertFalse("failed computations must not be cached", cache.containsKey("block"));

        // A later invocation must retry the computation instead of reading a
        // stale cached empty result.
        assertEquals(
            "fallback",
            BlockOptionalMeta.cacheOrCompute(cache, "block", key -> null, "fallback")
        );
        assertFalse("a retried failed computation must still not be cached", cache.containsKey("block"));

        // A successful computation (including a legitimately empty result, like a
        // BuiltInLootTables.EMPTY block) IS cached...
        assertEquals(
            "empty",
            BlockOptionalMeta.cacheOrCompute(cache, "block", key -> "empty", "fallback")
        );
        assertTrue("successful computations must be cached", cache.containsKey("block"));
        assertEquals("empty", cache.get("block"));

        // ...and subsequent calls are served from the cache without recomputing.
        assertEquals(
            "empty",
            BlockOptionalMeta.cacheOrCompute(cache, "block", key -> {
                throw new AssertionError("cached results must not be recomputed");
            }, "fallback")
        );
    }
}
