/*
 * Copyright (c) 2017, 2019, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot;

import static jdk.vm.ci.common.InitTimer.timer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import jdk.graal.compiler.serviceprovider.GlobalAtomicLong;
import org.graalvm.collections.EconomicMap;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionDescriptors;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.options.OptionsParser;

import jdk.vm.ci.common.InitTimer;
import jdk.vm.ci.common.NativeImageReinitialize;
import jdk.vm.ci.services.Services;

/**
 * The {@link #defaultOptions()} method returns the options values initialized in a HotSpot VM. The
 * values are set via system properties with the {@value #GRAAL_OPTION_PROPERTY_PREFIX} prefix.
 */
public class HotSpotGraalOptionValues {

    /**
     * The name of the system property specifying a file containing extra Graal option settings.
     */
    private static final String GRAAL_OPTIONS_FILE_PROPERTY_NAME = "jdk.graal.options.file";

    /**
     * The prefix for system properties that correspond to {@link Option} annotated fields. A field
     * named {@code MyOption} will have its value set from a system property with the name
     * {@code GRAAL_OPTION_PROPERTY_PREFIX + "MyOption"}.
     */
    public static final String GRAAL_OPTION_PROPERTY_PREFIX = "jdk.graal.";
    public static final String LEGACY_GRAAL_OPTION_PROPERTY_PREFIX = "graal.";

    /**
     * Prefix for system properties that correspond to libgraal Native Image options.
     */
    public static final String LIBGRAAL_VM_OPTION_PROPERTY_PREFIX = "jdk.graal.vm.";

    private static final Set<String> UNSUPPORTED_LIBGRAAL_PREFIXES = Set.of("jdk.libgraal.", "libgraal.");

    /**
     * Guard for issuing warning about deprecated Graal option prefix at most once.
     */
    private static final GlobalAtomicLong LEGACY_OPTION_DEPRECATION_WARNED = new GlobalAtomicLong(0L);

    /**
     * Guard for issuing warning about deprecated {@code jdk.graal.options.file} option at most
     * once.
     */
    private static final GlobalAtomicLong GRAAL_OPTIONS_FILE_DEPRECATION_WARNED = new GlobalAtomicLong(0L);

    /**
     * Gets the system property assignment that would set the current value for a given option.
     */
    public static String asSystemPropertySetting(OptionValues options, OptionKey<?> value) {
        return GRAAL_OPTION_PROPERTY_PREFIX + value.getName() + "=" + value.getValue(options);
    }

    @NativeImageReinitialize private static volatile OptionValues hotspotOptions;

    public static OptionValues defaultOptions() {
        OptionValues res = hotspotOptions;
        if (res == null) {
            synchronized (HotSpotGraalOptionValues.class) {
                res = hotspotOptions;
                if (res == null) {
                    res = initializeOptions();
                    hotspotOptions = res;
                }
            }
        }
        return res;
    }

    /**
     * Gets and parses options based on {@linkplain Services#getSavedProperties() saved system
     * properties}. The values for these options are initialized by parsing the file denoted by the
     * {@value #GRAAL_OPTIONS_FILE_PROPERTY_NAME} property followed by parsing the options encoded
     * in properties whose names start with {@value #GRAAL_OPTION_PROPERTY_PREFIX}. Key/value pairs
     * are parsed from the aforementioned file with {@link Properties#load(java.io.Reader)}.
     */
    @SuppressWarnings("try")
    public static EconomicMap<OptionKey<?>, Object> parseOptions() {
        EconomicMap<OptionKey<?>, Object> compilerOptionValues = OptionValues.newOptionMap();
        try (InitTimer t = timer("InitializeOptions")) {

            Iterable<OptionDescriptors> descriptors = OptionsParser.getOptionsLoader();
            Map<String, String> savedProps = jdk.vm.ci.services.Services.getSavedProperties();
            String optionsFile = savedProps.get(GRAAL_OPTIONS_FILE_PROPERTY_NAME);

            if (optionsFile != null) {
                if (GRAAL_OPTIONS_FILE_DEPRECATION_WARNED.compareAndSet(0L, 1L)) {
                    System.err.println("WARNING: The jdk.graal.options.file property is deprecated and will be ignored in a future release");
                }
                File graalOptions = new File(optionsFile);
                if (graalOptions.exists()) {
                    try (FileReader fr = new FileReader(graalOptions)) {
                        Properties props = new Properties();
                        props.load(fr);
                        EconomicMap<String, String> compilerOptionSettings = EconomicMap.create();
                        for (Map.Entry<Object, Object> e : props.entrySet()) {
                            compilerOptionSettings.put((String) e.getKey(), (String) e.getValue());
                        }
                        try {
                            OptionsParser.parseOptions(compilerOptionSettings, compilerOptionValues, descriptors);
                        } catch (Throwable e) {
                            throw new InternalError("Error parsing an option from " + graalOptions, e);
                        }
                    } catch (IOException e) {
                        throw new InternalError("Error reading " + graalOptions, e);
                    }
                }
            }

            EconomicMap<String, String> compilerOptionSettings = EconomicMap.create();
            EconomicMap<String, String> vmOptionSettings = EconomicMap.create();

            for (Map.Entry<String, String> e : savedProps.entrySet()) {
                String name = e.getKey();
                if (name.startsWith(LEGACY_GRAAL_OPTION_PROPERTY_PREFIX)) {
                    String baseName = name.substring(LEGACY_GRAAL_OPTION_PROPERTY_PREFIX.length());
                    name = GRAAL_OPTION_PROPERTY_PREFIX + baseName;
                    if (LEGACY_OPTION_DEPRECATION_WARNED.compareAndSet(0L, 1L)) {
                        System.err.printf("""
                                        WARNING: The 'graal.' property prefix for the Graal option %s
                                        WARNING: (and all other Graal options) is deprecated and will be ignored
                                        WARNING: in a future release. Please use 'jdk.graal.%s' instead.%n""",
                                        baseName, baseName);
                    }
                }
                if (name.startsWith(GRAAL_OPTION_PROPERTY_PREFIX)) {
                    if (name.startsWith(LIBGRAAL_VM_OPTION_PROPERTY_PREFIX)) {
                        vmOptionSettings.put(name.substring(LIBGRAAL_VM_OPTION_PROPERTY_PREFIX.length()), e.getValue());
                    } else if (name.equals(GRAAL_OPTIONS_FILE_PROPERTY_NAME)) {
                        // Ignore well known properties that do not denote an option
                    } else {
                        String value = e.getValue();
                        compilerOptionSettings.put(name.substring(GRAAL_OPTION_PROPERTY_PREFIX.length()), value);
                    }
                } else {
                    for (var prefix : UNSUPPORTED_LIBGRAAL_PREFIXES) {
                        if (name.startsWith(prefix)) {
                            String baseName = name.substring(prefix.length());
                            String msg = String.format("The '%s' property prefix is no longer supported. Use jdk.graal.vm.%s instead of %s%s.",
                                            prefix, baseName, prefix, baseName);
                            throw new IllegalArgumentException(msg);
                        }
                    }
                }
            }

            OptionsParser.parseOptions(compilerOptionSettings, compilerOptionValues, descriptors);
            notifyLibgraalOptions(compilerOptionValues, vmOptionSettings);
            return compilerOptionValues;
        }
    }

    /**
     * Substituted by
     * {@code com.oracle.svm.graal.hotspot.libgraal.Target_jdk_graal_compiler_hotspot_HotSpotGraalOptionValues}.
     *
     * @param compilerOptionValues parsed compiler option values
     * @param vmOptionSettings unparsed libgraal option values
     */
    private static void notifyLibgraalOptions(EconomicMap<OptionKey<?>, Object> compilerOptionValues, EconomicMap<String, String> vmOptionSettings) {
        if (!vmOptionSettings.isEmpty()) {
            System.err.printf("WARNING: Ignoring the following libgraal VM option(s) while executing jargraal: %s%n", String.join(", ", vmOptionSettings.getKeys()));
        }
    }

    /**
     * Substituted by
     * {@code com.oracle.svm.graal.hotspot.libgraal.Target_jdk_graal_compiler_hotspot_HotSpotGraalOptionValues}
     * to update {@code com.oracle.svm.core.option.RuntimeOptionValues.singleton()} instead of
     * creating a new {@link OptionValues} object.
     */
    private static OptionValues initializeOptions() {
        return new OptionValues(parseOptions());
    }

    static void printProperties(OptionValues compilerOptions, PrintStream out) {
        boolean all = HotSpotGraalCompilerFactory.Options.PrintPropertiesAll.getValue(compilerOptions);
        compilerOptions.printHelp(OptionsParser.getOptionsLoader(), out, GRAAL_OPTION_PROPERTY_PREFIX, all);
        if (all) {
            printLibgraalProperties(out, LIBGRAAL_VM_OPTION_PROPERTY_PREFIX);
        }
    }

    /**
     * Substituted by
     * {@code com.oracle.svm.graal.hotspot.libgraal.Target_jdk_graal_compiler_hotspot_HotSpotGraalOptionValues}.
     *
     * @param out where help is to be printed
     * @param prefix system property prefix for libgraal VM options
     */
    private static void printLibgraalProperties(PrintStream out, String prefix) {
    }
}
