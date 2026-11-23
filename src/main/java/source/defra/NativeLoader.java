// Copyright 2026 Democratized Data Foundation
//
// Use of this software is governed by the Business Source License
// included in the file licenses/BSL.txt.
//
// As of the Change Date specified in that file, in accordance with
// the Business Source License, use of this software will be governed
// by the Apache License, Version 2.0, included in the file
// licenses/APL.txt.

package source.defra;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

class NativeLoader {

    private static final boolean IS_ANDROID =
            System.getProperty("java.vm.name", "").contains("Dalvik");

    // All native libraries that must be present in the temp dir.
    // DefraDB is loaded as a dependency of nativewrapper via the embedded
    // $ORIGIN (Linux) or @loader_path (macOS) runtime search path,
    // so it must be extracted before nativewrapper is loaded.
    private static final String[] ALL_LIBS = {"defradb", "nativewrapper"};

    private static File tempDir;

    static synchronized void load(String libName) {
        if (IS_ANDROID) {
            System.loadLibrary(libName);
            return;
        }
        try {
            extractAll();
            // Only explicitly load nativewrapper — defradb is pulled in
            // automatically via the platform runtime search path in nativewrapper.
            if (libName.equals("nativewrapper")) {
                System.load(new File(tempDir, System.mapLibraryName("nativewrapper")).getAbsolutePath());
            }
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native libraries: " + e.getMessage());
        }
    }

    private static void extractAll() throws IOException {
        if (tempDir != null) return;
        tempDir = new File(System.getProperty("java.io.tmpdir"), "defradb-" + System.nanoTime());
        tempDir.mkdirs();
        for (String lib : ALL_LIBS) {
            extractLib(lib);
        }
    }

    private static void extractLib(String libName) throws IOException {
        String fileName = System.mapLibraryName(libName);
        File outFile = new File(tempDir, fileName);
        try (InputStream in = NativeLoader.class.getResourceAsStream("/" + fileName);
             FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("Native library not found in JAR: " + fileName);
            }
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
