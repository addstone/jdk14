/*
 * Copyright (c) 2015, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package jdk.vm.ci.hotspot;

import java.util.List;
import java.util.Set;

import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.common.NativeImageReinitialize;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime.Option;
import jdk.vm.ci.runtime.JVMCICompiler;
import jdk.vm.ci.runtime.JVMCICompilerFactory;
import jdk.vm.ci.runtime.JVMCIRuntime;
import jdk.vm.ci.services.JVMCIPermission;
import jdk.vm.ci.services.JVMCIServiceLocator;

import static jdk.vm.ci.services.Services.IS_IN_NATIVE_IMAGE;

final class HotSpotJVMCICompilerConfig {

    /**
     * This factory allows JVMCI initialization to succeed but raises an error if the VM asks JVMCI
     * to perform a compilation. This allows the reflective parts of the JVMCI API to be used
     * without requiring a compiler implementation to be available.
     */
    private static class DummyCompilerFactory implements JVMCICompilerFactory, JVMCICompiler {

        private final String reason;

        DummyCompilerFactory(String reason) {
            this.reason = reason;
        }

        @Override
        public HotSpotCompilationRequestResult compileMethod(CompilationRequest request) {
            throw new JVMCIError("no JVMCI compiler selected: " + reason);
        }

        @Override
        public String getCompilerName() {
            return "null";
        }

        @Override
        public JVMCICompiler createCompiler(JVMCIRuntime runtime) {
            return this;
        }
    }

    /**
     * Factory of the selected system compiler.
     */
    @NativeImageReinitialize private static JVMCICompilerFactory compilerFactory;

    /**
     * Gets the selected system compiler factory.
     *
     * @return the selected system compiler factory
     * @throws SecurityException if a security manager is present and it denies
     *             {@link JVMCIPermission} for any {@link JVMCIServiceLocator} loaded by this method
     */
    static JVMCICompilerFactory getCompilerFactory() {
        if (compilerFactory == null) {
            JVMCICompilerFactory factory = null;
            String compilerName = Option.Compiler.getString();
            if (compilerName != null) {
                if (compilerName.isEmpty()) {
                    factory = new DummyCompilerFactory(" empty \"\" is specified");
                } else if (compilerName.equals("null")) {
                    factory = new DummyCompilerFactory("\"null\" is specified");
                } else {
                    for (JVMCICompilerFactory f : getJVMCICompilerFactories()) {
                        if (f.getCompilerName().equals(compilerName)) {
                            factory = f;
                        }
                    }
                    if (factory == null) {
                        throw new JVMCIError("JVMCI compiler '%s' not found", compilerName);
                    }
                }
            } else {
                // Auto select a single available compiler
                String reason = "default compiler is not found";
                for (JVMCICompilerFactory f : getJVMCICompilerFactories()) {
                    if (factory == null) {
                        openJVMCITo(f.getClass().getModule());
                        factory = f;
                    } else {
                        // Multiple factories seen - cancel auto selection
                        reason = "multiple factories seen: \"" + factory.getCompilerName() + "\" and \"" + f.getCompilerName() + "\"";
                        factory = null;
                        break;
                    }
                }
                if (factory == null) {
                    factory = new DummyCompilerFactory(reason);
                }
            }
            factory.onSelection();
            compilerFactory = factory;
        }
        return compilerFactory;
    }

    /**
     * Opens all JVMCI packages to {@code otherModule}.
     */
    private static void openJVMCITo(Module otherModule) {
        if (!IS_IN_NATIVE_IMAGE) {
            Module jvmci = HotSpotJVMCICompilerConfig.class.getModule();
            if (jvmci != otherModule) {
                Set<String> packages = jvmci.getPackages();
                for (String pkg : packages) {
                    boolean opened = jvmci.isOpen(pkg, otherModule);
                    if (!opened) {
                        jvmci.addOpens(pkg, otherModule);
                    }
                }
            }
        }
    }

    private static List<JVMCICompilerFactory> getJVMCICompilerFactories() {
        return JVMCIServiceLocator.getProviders(JVMCICompilerFactory.class);
    }
}
