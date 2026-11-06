import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Minimal structure-template (.nbt) writer: palette of block states + list of non-air blocks. */
public class Nbt {
    final int sx, sy, sz;
    final Map<String, Integer> palette = new LinkedHashMap<>();
    final List<int[]> blocks = new ArrayList<>();
    final List<String> blockNbt = new ArrayList<>();
    Nbt(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }

    void set(int x, int y, int z, String state) { set(x, y, z, state, null); }
    void set(int x, int y, int z, String state, String nbtSnbtUnused) {
        Integer id = palette.get(state);
        if (id == null) { id = palette.size(); palette.put(state, id); }
        blocks.add(new int[]{x, y, z, id});
    }

    static void str(DataOutputStream o, String s) throws IOException { byte[] b = s.getBytes("UTF-8"); o.writeShort(b.length); o.write(b); }
    static void head(DataOutputStream o, int type, String name) throws IOException { o.writeByte(type); str(o, name); }

    void write(File f, int dataVersion) throws IOException {
        f.getParentFile().mkdirs();
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f), 65536), 65536))) {
            head(o, 10, "");
            head(o, 3, "DataVersion"); o.writeInt(dataVersion);
            head(o, 9, "size"); o.writeByte(3); o.writeInt(3); o.writeInt(sx); o.writeInt(sy); o.writeInt(sz);
            head(o, 9, "palette"); o.writeByte(10); o.writeInt(palette.size());
            for (String st : palette.keySet()) {
                String name = st; Map<String, String> props = new LinkedHashMap<>();
                int i = st.indexOf('[');
                if (i >= 0) {
                    name = st.substring(0, i);
                    for (String kv : st.substring(i + 1, st.length() - 1).split(",")) { String[] p = kv.split("=", 2); props.put(p[0], p[1]); }
                }
                head(o, 8, "Name"); str(o, name.contains(":") ? name : "minecraft:" + name);
                if (!props.isEmpty()) {
                    head(o, 10, "Properties");
                    for (var e : props.entrySet()) { head(o, 8, e.getKey()); str(o, e.getValue()); }
                    o.writeByte(0);
                }
                o.writeByte(0);
            }
            head(o, 9, "blocks"); o.writeByte(10); o.writeInt(blocks.size());
            for (int[] b : blocks) {
                head(o, 9, "pos"); o.writeByte(3); o.writeInt(3); o.writeInt(b[0]); o.writeInt(b[1]); o.writeInt(b[2]);
                head(o, 3, "state"); o.writeInt(b[3]);
                o.writeByte(0);
            }
            head(o, 9, "entities"); o.writeByte(10); o.writeInt(0);
            o.writeByte(0);
        }
    }

    public static void main(String[] a) throws Exception {
        Nbt n = new Nbt(3, 1, 1);
        n.set(0, 0, 0, "minecraft:blackstone"); n.set(1, 0, 0, "minecraft:magma_block"); n.set(2, 0, 0, "minecraft:red_concrete");
        n.write(new File(a[0]), 5023);
    }
}
