package net.rasanovum.roxy.loader;

import cpw.mods.jarhandling.JarContents;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

final class RoxyJarContents {
    private RoxyJarContents() {
    }

    static boolean containsFile(JarContents contents, String path) {
        return contents.findFile(path.replace('\\', '/')).isPresent();
    }

    static InputStream openFile(JarContents contents, String path) throws IOException {
        URI uri = contents.findFile(path.replace('\\', '/'))
                .orElseThrow(() -> new IOException("Missing jar entry " + path));
        return uri.toURL().openStream();
    }
}
