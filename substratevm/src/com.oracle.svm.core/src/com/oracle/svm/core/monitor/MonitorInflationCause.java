/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, 2023, Red Hat Inc. All rights reserved.
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

package com.oracle.svm.core.monitor;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

public enum MonitorInflationCause {
    VM_INTERNAL("VM Internal"),
    MONITOR_ENTER("Monitor Enter"),
    WAIT("Monitor Wait"),
    NOTIFY("Monitor Notify"),
    JNI_ENTER("JNI Monitor Enter"),
    JNI_EXIT("JNI Monitor Exit"),
    ///TODO @dprcci add??
    JVMTI_CREATE("JVMTI CreateRawMonitor"),
    JVMTI_DESTROY("JVMTI DestroyRawMonitor"),
    JVMTI_ENTER("JVMTI RawMonitorEnter"),
    JVMTI_EXIT("JVMTI RawMonitorExit"),
    JVMTI_WAIT("JVMTI RawMonitorWait"),
    JVMTI_NOTIFY("JVMTI RawMonitorNotify"),
    JVMTI_NOTIFYALL("JVMTI RawMonitorNotifyAll");

    private final String text;

    @Platforms(Platform.HOSTED_ONLY.class)
    MonitorInflationCause(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
