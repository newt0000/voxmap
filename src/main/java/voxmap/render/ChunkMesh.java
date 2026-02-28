package voxmap.render;

public class ChunkMesh {
    public final float[] vertices;
    public final float[] normals;
    public final float[] uvs;

    // NEW: per-vertex colors RGBRGB...
    public final float[] colors;

    public final int[] indices;

    // NEW: light emitters [x,y,z,intensity]...
    public final float[] emitters;

    public ChunkMesh(float[] vertices, float[] normals, float[] uvs, float[] colors, int[] indices, float[] emitters) {
        this.vertices = vertices;
        this.normals = normals;
        this.uvs = uvs;
        this.colors = colors;
        this.indices = indices;
        this.emitters = emitters;
    }

    public boolean isEmpty() {
        return vertices == null || vertices.length == 0 || indices == null || indices.length == 0;
    }
}