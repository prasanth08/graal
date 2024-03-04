/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jvmti;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.core.common.NumUtil;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.impl.UnmanagedMemorySupport;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.jvmti.headers.JvmtiCapabilities;
import com.oracle.svm.core.jvmti.headers.JvmtiError;
import com.oracle.svm.core.jvmti.headers.JvmtiEvent;
import com.oracle.svm.core.jvmti.headers.JvmtiEventCallbacks;
import com.oracle.svm.core.jvmti.headers.JvmtiExternalEnv;
import com.oracle.svm.core.jvmti.headers.JvmtiInterface;

public final class JvmtiEnvUtil {
    private static final int VALID_ENV_MAGIC = 0x71EE;
    private static final int DISPOSED_ENV_MAGIC = 0xDEFC;

    @Platforms(Platform.HOSTED_ONLY.class)
    private JvmtiEnvUtil() {
    }

    public static JvmtiExternalEnv allocate() {
        JvmtiInterface functionTable = JvmtiFunctionTable.allocateFunctionTable();
        if (functionTable.isNull()) {
            return WordFactory.nullPointer();
        }

        JvmtiEnv env = ImageSingletons.lookup(UnmanagedMemorySupport.class).calloc(WordFactory.unsigned(internalEnvSize()));
        if (env.isNull()) {
            ImageSingletons.lookup(UnmanagedMemorySupport.class).free(functionTable);
            return WordFactory.nullPointer();
        }

        env.setIsolate(CurrentIsolate.getIsolate());
        env.setMagic(VALID_ENV_MAGIC);

        JvmtiEnvEventEnabledUtils.initialize(getEnvEventEnabled(env));

        JvmtiExternalEnv externalEnv = toExternal(env);
        externalEnv.setFunctions(functionTable);

        return externalEnv;
    }

    /*
     * Note that HotSpot doesn't necessarily free the environment right away to prevent certain
     * races. Depending on the functionality that we are going to add to our JVMTI implementation,
     * we might need the same approach.
     */
    public static void free(JvmtiEnv env) {

        JvmtiCapabilities capabilities = getCapabilities(env);
        relinquishCapabilities(env, capabilities);

        env.setMagic(DISPOSED_ENV_MAGIC);
        JvmtiExternalEnv externalEnv = toExternal(env);
        JvmtiFunctionTable.freeFunctionTable(externalEnv.getFunctions());
        externalEnv.setFunctions(WordFactory.nullPointer());

        ImageSingletons.lookup(UnmanagedMemorySupport.class).free(env);
    }

    public static void initialize(JvmtiEnv env, JvmtiEnvShared envShared) {
        assert isValid(env);
        assert envShared.isNonNull();

        env.setEnvShared(envShared);
        env.setNextEnv(WordFactory.nullPointer());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static JvmtiEnv toInternal(JvmtiExternalEnv externalEnv) {
        assert externalEnv.isNonNull();
        return (JvmtiEnv) ((Pointer) externalEnv).subtract(externalEnvOffset());
    }

    public static JvmtiExternalEnv toExternal(JvmtiEnv env) {
        assert env.isNonNull();
        return (JvmtiExternalEnv) ((Pointer) env).add(externalEnvOffset());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isValid(JvmtiEnv env) {
        assert env.isNonNull();
        return env.getMagic() == VALID_ENV_MAGIC;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static Isolate getIsolate(JvmtiEnv env) {
        assert isValid(env);
        return env.getIsolate();
    }

    public static void setNextEnvironment(JvmtiEnv env, JvmtiEnv next) {
        assert isValid(env);
        assert isValid(next);
        env.setNextEnv(next);
    }

    public static JvmtiEnv getNextEnvironment(JvmtiEnv env) {
        assert isValid(env);
        return env.getNextEnv();
    }

    // ------------------ Capabilities API ------------------ //
    // TODO @dprcci add to the jvmti api (jvmtifunctions)
    public static JvmtiError getPotentialCapabilities(JvmtiEnv env, JvmtiCapabilities result) {
        assert isValid(env);
        return JvmtiCapabilitiesUtil.getPotentialCapabilities(env.getPhase(), getCapabilities(env), getProhibitedCapabilities(env), env.getEnvShared(), result);
    }

    public static JvmtiCapabilities getCapabilities(JvmtiEnv env) {
        assert isValid(env);
        return addOffset(env, capabilitiesOffset());
    }

    public static JvmtiError addCapabilities(JvmtiEnv env, JvmtiCapabilities capabilities) {
        assert capabilities.isNonNull();
        return JvmtiCapabilitiesUtil.addCapabilities(env.getPhase(), getEnvShared(env), getCapabilities(env), getProhibitedCapabilities(env), capabilities);
    }

    public static JvmtiError relinquishCapabilities(JvmtiEnv env, JvmtiCapabilities unwanted) {
        assert unwanted.isNonNull();
        return JvmtiCapabilitiesUtil.relinquishCapabilities(getEnvShared(env), getCapabilities(env), unwanted);
    }

    // ------------------------------------------------------ //

    public static JvmtiCapabilities getProhibitedCapabilities(JvmtiEnv env) {
        assert isValid(env);
        return addOffset(env, prohibitedCapabilitiesOffset());
    }

    public static JvmtiEnvEventEnabled getEnvEventEnabled(JvmtiEnv env) {
        assert isValid(env);
        return addOffset(env, envEventEnabledOffset());
    }

    public static JvmtiEventCallbacks getEventCallbacks(JvmtiEnv env) {
        assert isValid(env);
        return addOffset(env, eventCallbacksOffset());
    }

    public static void setEventCallbacks(JvmtiEnv env, JvmtiEventCallbacks newCallbacks, int sizeOfCallbacks) {

        flushObjectFreeEvent(env);

        JvmtiEventCallbacks eventCallbacks = getEventCallbacks(env);

        JvmtiEventCallbacksUtil.setEventCallbacks(eventCallbacks, newCallbacks, sizeOfCallbacks);
        JvmtiEnvEventEnabledUtils.setEventCallbacksEnabled(getEnvEventEnabled(env), eventCallbacks);

        JvmtiEnvEventEnabledUtils.recomputeEnabled();

    }

    /*
     * Compute truly enabled events - meaning if the event can and could be sent.
     *
     * An event is truly enabled if it is user enabled on the thread or globally user enabled, but
     * only if there is a callback or event hook for it and, for field watch and frame pop, one has
     * been set. Compute if truly enabled, per thread, per environment, per combination (thread x
     * environment), and overall. These merges are true if any is true. True per thread if some
     * environment has callback set and the event is globally enabled or enabled for this thread.
     * True per environment if the callback is set and the event is globally enabled in this
     * environment or enabled for any thread in this environment. True per combination if the
     * environment has the callback set and the event is globally enabled in this environment or the
     * event is enabled for this thread and environment.
     *
     * All states transitions dependent on these transitions are also handled here.
     */

    public static JvmtiEnvShared getEnvShared(JvmtiEnv env) {
        assert isValid(env);
        return env.getEnvShared();
    }

    public static void setEnvShared(JvmtiEnv env, JvmtiEnvShared envShared) {
        assert isValid(env);
        env.setEnvShared(envShared);
    }

    @SuppressWarnings("unchecked")
    private static <T extends PointerBase> T addOffset(JvmtiEnv env, int offset) {
        return (T) ((Pointer) env).add(offset);
    }

    public static boolean hasCapability(JvmtiExternalEnv envExt, JvmtiCapabilitiesEnum cap){
        JvmtiEnv internal = toInternal(envExt);
        assert isValid(internal);
        return JvmtiCapabilitiesUtil.hasCapability(getCapabilities(internal), cap);
    }

    // TODO @dprcci implement
    public static boolean hasEventCapability(JvmtiEnv env, JvmtiEvent eventInfo) {
        /* At the moment, we only support events that don't need any specific capabilities. */
        return true;
    }

    @Fold
    static int capabilitiesOffset() {
        return NumUtil.roundUp(SizeOf.get(JvmtiEnv.class), ConfigurationValues.getTarget().wordSize);
    }

    @Fold
    static int prohibitedCapabilitiesOffset() {
        return NumUtil.roundUp(capabilitiesOffset() + SizeOf.get(JvmtiCapabilities.class), ConfigurationValues.getTarget().wordSize);
    }

    @Fold
    static int envEventEnabledOffset() {
        return NumUtil.roundUp(prohibitedCapabilitiesOffset() + SizeOf.get(JvmtiCapabilities.class), ConfigurationValues.getTarget().wordSize);
    }

    @Fold
    static int eventCallbacksOffset() {
        return NumUtil.roundUp(envEventEnabledOffset() + SizeOf.get(JvmtiEnvEventEnabled.class), ConfigurationValues.getTarget().wordSize);
    }

    @Fold
    static int externalEnvOffset() {
        return NumUtil.roundUp(eventCallbacksOffset() + SizeOf.get(JvmtiEventCallbacks.class), ConfigurationValues.getTarget().wordSize);
    }

    @Fold
    static int internalEnvSize() {
        return externalEnvOffset() + SizeOf.get(JvmtiExternalEnv.class);
    }

    // TODO @dprcci complete
    public static void setEventUserEnabled(JvmtiEnv env, Thread javaEventThread, JvmtiEvent eventType, boolean value) {
        // TEMP (chaeubl): implement
        /*
         * if (thread == null && thread_oop_h() == nullptr) { // Null thread and null thread_oop now
         * indicate setting globally instead of setting thread specific since null thread by itself
         * means an unmounted virtual thread. env->env_event_enable()->set_user_enabled(event_type,
         * enabled); } else { // create the thread state (if it didn't exist before)
         * JvmtiThreadState *state = JvmtiThreadState::state_for_while_locked(thread,
         * thread_oop_h()); if (state != nullptr) {
         * state->env_thread_state(env)->event_enable()->set_user_enabled(event_type, enabled); } }
         * recompute_enabled();
         */
        if (eventType == JvmtiEvent.JVMTI_EVENT_OBJECT_FREE) {
            flushObjectFreeEvent(env);
        }

        if (javaEventThread == null) {
            JvmtiEnvEventEnabledUtils.setUserEventEnabled(getEnvEventEnabled(env), eventType, value);
        }
    }

    // TODO dprcci implement? Would need TagMap (or equivalent) first
    private static void flushObjectFreeEvent(JvmtiEnv env) {
    }
}
