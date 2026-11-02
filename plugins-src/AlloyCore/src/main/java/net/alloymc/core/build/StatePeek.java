package net.alloymc.core.build;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Debug helper: prints raw palette entries that aren't plain strings, from one region file. */
public final class StatePeek {
    public static void main(String[] a) throws Exception {
        byte[] data = Files.readAllBytes(new File(a[0]).toPath());
        int shown = 0;
        for (int i = 0; i < 1024 && shown < 6; i++) {
            int loc = ((data[i * 4] & 255) << 16) | ((data[i * 4 + 1] & 255) << 8) | (data[i * 4 + 2] & 255);
            if (loc == 0) continue;
            int off = loc * 4096;
            int len = ((data[off] & 255) << 24) | ((data[off + 1] & 255) << 16) | ((data[off + 2] & 255) << 8) | (data[off + 3] & 255);
            DataInputStream din = new DataInputStream(new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(data, off + 5, len - 1))));
            int t = din.readByte(); din.readUTF();
            Map<String, Object> root = WorldCam.obj(WorldCam.nbt(din, t));
            for (Object o : WorldCam.arr(root.get("sections"))) {
                Map<String, Object> bs = WorldCam.obj(WorldCam.obj(o).get("block_states"));
                if (bs == null) continue;
                for (Object pe : WorldCam.arr(bs.get("palette")))
                    if (pe instanceof Map<?, ?> m && !m.containsKey("") && shown < 6) { System.out.println(pe + "  (" + pe.getClass().getSimpleName() + ")"); shown++; }
                    else if (pe instanceof Map<?, ?> m && String.valueOf(m.get("")).contains("leaves") && shown < 6) { System.out.println("wrapped: " + pe); shown++; }
            }
        }
    }
}
