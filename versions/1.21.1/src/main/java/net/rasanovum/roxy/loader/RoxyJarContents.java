package net.rasanovum.roxy.loader;

import cpw.mods.jarhandling.JarContents;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Optional;

final class RoxyJarContents {
    private RoxyJarContents() {
    }

    static boolean containsFile(JarContents contents, String path) {
        return contents.findFile(path.replace('\\', '/')).isPresent();
    }

    static InputStream openFile(JarContents contents, String path) throws IOException {
        Optional<URI> uri = contents.findFile(path.replace('\\', '/'));
        if (uri.isEmpty()) return null;
        URLConnection connection = uri.get().toURL().openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }
}
