package dev.totem.observer.bridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Compact deterministic wire encoding for one 16x16x16 block-state section. */
final class ObserverWorldSectionCodec {
    static final int PROTOCOL = 1;
    static final int PARTS = 4;
    static final int STATES_PER_PART = 1024;
    static final int STATES_PER_SECTION = PARTS * STATES_PER_PART;
    static final int MAX_STATE_ID = 999_999;

    private ObserverWorldSectionCodec() {}

    static String encodePart(int[] stateIds, int part) {
        if (stateIds == null || stateIds.length != STATES_PER_SECTION || part < 0 || part >= PARTS) {
            throw new IllegalArgumentException("Invalid section part");
        }
        var bytes = new ByteArrayOutputStream(STATES_PER_PART * 2);
        int start = part * STATES_PER_PART;
        int end = start + STATES_PER_PART;
        for (int index = start; index < end; index++) writeUnsignedVarInt(bytes, stateIds[index]);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    static List<Integer> decodePart(String encoded) {
        if (encoded == null || encoded.length() > 4096 || (encoded.length() & 3) != 0
                || !encoded.matches("[A-Za-z0-9+/]*={0,2}")) {
            throw new IllegalArgumentException("Invalid section encoding");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid section base64", failure);
        }
        var values = new ArrayList<Integer>(STATES_PER_PART);
        int cursor = 0;
        while (cursor < bytes.length) {
            int value = 0;
            int shift = 0;
            int count = 0;
            int last = 0;
            while (true) {
                if (cursor >= bytes.length || count == 3) throw new IllegalArgumentException("Invalid section varint");
                int next = bytes[cursor++] & 0xff;
                last = next & 0x7f;
                value |= last << shift;
                count++;
                if ((next & 0x80) == 0) break;
                shift += 7;
            }
            if ((count > 1 && last == 0) || value < 0 || value > MAX_STATE_ID) {
                throw new IllegalArgumentException("Non-canonical section varint");
            }
            values.add(value);
            if (values.size() > STATES_PER_PART) throw new IllegalArgumentException("Too many section states");
        }
        if (values.size() != STATES_PER_PART) throw new IllegalArgumentException("Incomplete section states");
        return List.copyOf(values);
    }

    private static void writeUnsignedVarInt(ByteArrayOutputStream output, int value) {
        if (value < 0 || value > MAX_STATE_ID) throw new IllegalArgumentException("Invalid block-state id");
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }
}
