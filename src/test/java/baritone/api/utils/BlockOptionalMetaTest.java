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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BlockOptionalMetaTest {

    /**
     * Regression test: {@code BlockOptionalMeta.ServerLevelStub} used to reload
     * datapack registries ({@code RegistryDataLoader}) from its static initializer.
     * When that reload failed -- as it does under NeoForge/Sinytra Connector for the
     * empty painting_variant and wolf_variant registries -- the JVM permanently
     * poisoned the class: the first use threw {@link ExceptionInInitializerError}
     * and every later use threw {@link NoClassDefFoundError}, breaking {@code #mine}
     * forever.
     *
     * <p>This plain-JVM JUnit environment has no running Minecraft instance, so any
     * registry reload attempted during class initialization would fail here too.
     * Initializing the class must therefore succeed, and the registry access must
     * not have been eagerly loaded: it is resolved lazily on first use (live
     * client/server registry access when valid, a reload only as a non-fatal
     * fallback).
     */
    @Test
    public void serverLevelStubInitializationDoesNotLoadRegistries() throws Exception {
        // Class.forName initializes the class; a failing <clinit> would throw
        // ExceptionInInitializerError here, and the JVM would then report
        // NoClassDefFoundError for every later use.
        Class<?> stub = Class.forName("baritone.api.utils.BlockOptionalMeta$ServerLevelStub");

        // The registry access field must still be null after initialization,
        // proving that no registry reload ran (and thus none could fail) in <clinit>.
        Field registryAccess = stub.getDeclaredField("registryAccess");
        registryAccess.setAccessible(true);
        assertNull(
            "ServerLevelStub must not eagerly load registry access during class initialization",
            registryAccess.get(null)
        );
    }

    /**
     * Regression test: a failed drop lookup must not be permanently cached as an
     * empty drop list. {@code BlockOptionalMeta.drops} previously used a static
     * {@code computeIfAbsent} cache: when the lazy registry lookup failed (no live
     * Level registry access), the empty result was cached forever, so a later
     * in-world {@code #mine} could never retry and recover the real drops.
     *
     * <p>This test deliberately does NOT claim real Minecraft integration coverage:
     * this plain-JVM JUnit environment has no running Minecraft instance, so the
     * drop computation can never succeed. It drives the real private
     * {@code drops(Block)} method with a Block allocated without its constructor
     * (the same {@code Unsafe.allocateInstance} technique
     * {@code ServerLevelStub.fastCreate()} uses), which has no loot table and no
     * registry access -- so every lookup is guaranteed to fail exactly like a
     * registry-unavailable lookup fails in production. The contract pinned here is
     * the cache-retry behavior the in-game flow depends on: failures are retried,
     * not cached.
     */
    @Test
    public void failedDropLookupIsNotCached() throws Exception {
        // Constructor-less Block: no loot table, no state definition, no registry
        // bootstrap -- guaranteed plain-JVM-safe. Loaded without initialization
        // since only the Class object and an unconstructed instance are needed.
        Class<?> blockClass = Class.forName(
            "net.minecraft.world.level.block.Block",
            false,
            BlockOptionalMetaTest.class.getClassLoader()
        );
        Object block = allocateInstance(blockClass);

        Method drops = BlockOptionalMeta.class.getDeclaredMethod("drops", blockClass);
        drops.setAccessible(true);

        // Two consecutive failed lookups: each must return an empty drop list and
        // must NOT populate the cache, so a later invocation can retry.
        assertTrue("a failed lookup must return an empty list, not throw", ((List<?>) drops.invoke(null, block)).isEmpty());
        assertTrue("a retried lookup must still return an empty list", ((List<?>) drops.invoke(null, block)).isEmpty());

        Map<?, ?> cache = (Map) dropsCacheField().get(null);
        assertFalse(
            "a failed drop lookup must not be permanently cached as empty; a later #mine must be able to retry",
            cache.containsKey(block)
        );

        // Positive control: a block with a legitimately empty loot table is a
        // successful computation and must still be cached.
        lootTableField(blockClass).set(block, emptyLootTableKey());
        drops.invoke(null, block);
        assertTrue(
            "a legitimate empty loot table is a successful lookup and must still be cached",
            cache.containsKey(block)
        );
    }

    private static Object allocateInstance(Class<?> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    private static Field lootTableField(Class<?> blockClass) throws Exception {
        for (Class<?> c = blockClass; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals("net.minecraft.resources.ResourceKey")) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new AssertionError("no loot-table field found on Block hierarchy");
    }

    private static Field dropsCacheField() throws Exception {
        Field drops = BlockOptionalMeta.class.getDeclaredField("drops");
        drops.setAccessible(true);
        return drops;
    }

    private static Object emptyLootTableKey() throws Exception {
        return Class.forName("net.minecraft.world.level.storage.loot.BuiltInLootTables")
            .getField("EMPTY")
            .get(null);
    }
}
