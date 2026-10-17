package net.rasanovum.roxy.blend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL45C.*;

public final class RoxyTranslucentBoundary {
    private static final int TEXTURE_UNIT = 4;
    private static final int MAX_GRID_CELLS = 4_000_000;

    private final Set<Section> sections = new HashSet<>();
    private final Map<Integer, Uniforms> uniformsByProgram = new HashMap<>();
    private ByteBuffer uploadData;

    private int texture;
    private int sampler;
    private int textureWidth;
    private int textureHeight;
    private int textureDepth;
    private int originX;
    private int originY;
    private int originZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private boolean uploaded;

    public void clear() {
        sections.clear();
        uploaded = false;
        sizeX = 0;
        sizeY = 0;
        sizeZ = 0;
    }

    public void add(int sectionX, int sectionY, int sectionZ) {
        sections.add(new Section(sectionX, sectionY, sectionZ));
        uploaded = false;
    }

    public void upload() {
        uploaded = false;
        sizeX = 0;
        sizeY = 0;
        sizeZ = 0;
        if (sections.isEmpty()) return;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Section section : sections) {
            minX = Math.min(minX, section.x);
            minY = Math.min(minY, section.y);
            minZ = Math.min(minZ, section.z);
            maxX = Math.max(maxX, section.x);
            maxY = Math.max(maxY, section.y);
            maxZ = Math.max(maxZ, section.z);
        }

        long width = (long) maxX - minX + 1L;
        long height = (long) maxY - minY + 1L;
        long depth = (long) maxZ - minZ + 1L;
        if (width < 1L || height < 1L || depth < 1L
                || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE || depth > Integer.MAX_VALUE) {
            throw new IllegalStateException("Native translucent section bounds are not representable");
        }
        long cells = width * height;
        if (cells > MAX_GRID_CELLS || cells * depth > MAX_GRID_CELLS) {
            throw new IllegalStateException("Native translucent section mask exceeds the 4M-cell limit");
        }

        int maxTextureSize = glGetInteger(GL_MAX_3D_TEXTURE_SIZE);
        if (maxTextureSize < 1 || width > maxTextureSize || height > maxTextureSize || depth > maxTextureSize) {
            throw new IllegalStateException("Native translucent section mask exceeds GL_MAX_3D_TEXTURE_SIZE");
        }

        int gridWidth = (int) width;
        int gridHeight = (int) height;
        int gridDepth = (int) depth;
        int cellCount = (int) (cells * depth);

        ensureTexture(capacity(gridWidth, maxTextureSize), capacity(gridHeight, maxTextureSize),
                capacity(gridDepth, maxTextureSize));
        ensureUploadBuffer(cellCount);
        for (int index = 0; index < cellCount; index++) uploadData.put(index, (byte) 0);

        for (Section section : sections) {
            int x = section.x - minX;
            int y = section.y - minY;
            int z = section.z - minZ;
            int index = (z * gridHeight + y) * gridWidth + x;
            uploadData.put(index, (byte) 1);
        }

        int oldAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        int oldRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int oldImageHeight = glGetInteger(GL_UNPACK_IMAGE_HEIGHT);
        int oldSkipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        int oldSkipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
        int oldSkipImages = glGetInteger(GL_UNPACK_SKIP_IMAGES);
        int oldUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
            uploadData.position(0);
            uploadData.limit(cellCount);
            glTextureSubImage3D(texture, 0, 0, 0, 0, gridWidth, gridHeight, gridDepth,
                    GL_RED_INTEGER, GL_UNSIGNED_BYTE, uploadData);
        } finally {
            glPixelStorei(GL_UNPACK_ALIGNMENT, oldAlignment);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, oldRowLength);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, oldImageHeight);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, oldSkipPixels);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, oldSkipRows);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, oldSkipImages);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, oldUnpackBuffer);
            uploadData.clear();
        }

        originX = minX;
        originY = minY;
        originZ = minZ;
        sizeX = gridWidth;
        sizeY = gridHeight;
        sizeZ = gridDepth;
        uploaded = true;
    }

    public void bind(int program) {
        if (program == 0) return;
        Uniforms uniforms = uniformsByProgram.computeIfAbsent(program, Uniforms::resolve);
        if (uniforms.enabled < 0) return;

        boolean active = uploaded && texture != 0 && sampler != 0
                && uniforms.origin >= 0 && uniforms.size >= 0;
        glUniform1i(uniforms.enabled, active ? 1 : 0);
        if (!active) return;

        glUniform3i(uniforms.origin, originX, originY, originZ);
        glUniform3i(uniforms.size, sizeX, sizeY, sizeZ);
        glBindTextureUnit(TEXTURE_UNIT, texture);
        glBindSampler(TEXTURE_UNIT, sampler);
    }

    public void free() {
        sections.clear();
        uniformsByProgram.clear();
        uploadData = null;
        uploaded = false;
        sizeX = sizeY = sizeZ = 0;
        originX = originY = originZ = 0;
        if (texture != 0) glDeleteTextures(texture);
        if (sampler != 0) glDeleteSamplers(sampler);
        texture = sampler = 0;
        textureWidth = textureHeight = textureDepth = 0;
    }

    private void ensureTexture(int width, int height, int depth) {
        if (texture != 0 && width <= textureWidth && height <= textureHeight && depth <= textureDepth) return;

        if (texture != 0) glDeleteTextures(texture);
        texture = glCreateTextures(GL_TEXTURE_3D);
        textureWidth = width;
        textureHeight = height;
        textureDepth = depth;
        glTextureStorage3D(texture, 1, GL_R8UI, textureWidth, textureHeight, textureDepth);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

        if (sampler == 0) {
            sampler = glCreateSamplers();
            glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        }
    }

    private void ensureUploadBuffer(int cellCount) {
        if (uploadData != null && uploadData.capacity() >= cellCount) return;
        int capacity = Math.max(256, cellCount);
        if (uploadData != null) capacity = Math.max(capacity, Math.min(MAX_GRID_CELLS, uploadData.capacity() + uploadData.capacity() / 2));
        uploadData = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    private static int capacity(int size, int maxTextureSize) {
        int rounded = 1;
        while (rounded < size && rounded <= maxTextureSize / 2) rounded <<= 1;
        return rounded >= size ? rounded : size;
    }

    private record Section(int x, int y, int z) {
    }

    private record Uniforms(int enabled, int origin, int size) {
        static Uniforms resolve(int program) {
            return new Uniforms(glGetUniformLocation(program, "roxyNativeTranslucentEnabled"),
                    glGetUniformLocation(program, "roxyNativeTranslucentOrigin"),
                    glGetUniformLocation(program, "roxyNativeTranslucentSize"));
        }
    }
}
