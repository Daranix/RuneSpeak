package com.runespeak.translate;

import com.runespeak.translate.tensor.Tensor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OnnxWeightReader {
    private final Map<String, Tensor> weights = new HashMap<>();

    // TensorProto.DataType enum values
    private static final int DT_FLOAT = 1;
    private static final int DT_UINT8 = 2;
    private static final int DT_INT8 = 3;
    private static final int DT_INT32 = 6;
    private static final int DT_INT64 = 7;

    public OnnxWeightReader(Path onnxPath) throws IOException {
        byte[] data = Files.readAllBytes(onnxPath);
        parseModelProto(data);

        // Add short-name aliases for any model.* prefixed names (e.g. model.shared.weight -> shared.weight)
        List<String> toAdd = new ArrayList<>();
        for (String key : new ArrayList<>(weights.keySet())) {
            if (key.startsWith("model.")) {
                String shortName = key.substring("model.".length());
                if (!weights.containsKey(shortName)) {
                    toAdd.add(shortName);
                }
            }
        }
        for (String alias : toAdd) {
            String fullName = "model." + alias;
            weights.put(alias, weights.get(fullName));
        }

        log.info("Loaded {} weight tensors from {}", weights.size(), onnxPath.getFileName());
    }

    public Tensor get(String name) {
        Tensor t = weights.get(name);
        if (t == null) throw new IllegalArgumentException("Weight not found: " + name);
        return t;
    }

    public boolean has(String name) {
        return weights.containsKey(name);
    }

    public Map<String, Tensor> getAll() {
        return weights;
    }

    // ─── ModelProto ─────────────────────────────────────────────

    private void parseModelProto(byte[] data) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        while (reader.remaining() > 0) {
            int tag = reader.readTag();
            if (tag < 0) break;
            int fieldNum = tag >> 3;
            int wireType = tag & 7;

            if (fieldNum == 7 && wireType == 2) {
                int graphLen = reader.readVarint32();
                byte[] graphData = reader.readBytes(graphLen);
                parseGraphProto(graphData);
            } else {
                reader.skipField(wireType);
            }
        }
    }

    // ─── GraphProto ─────────────────────────────────────────────
    // field 1: node  (repeated NodeProto)
    // field 5: initializer (repeated TensorProto) — tag 0x2A

    private void parseGraphProto(byte[] data) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        // Collect aliases from graph nodes before/while parsing initializers
        List<String[]> aliases = new ArrayList<>();
        List<String> graphInputs = new ArrayList<>();
        List<String> graphOutputs = new ArrayList<>();
        reader.pos = 0;

        while (reader.remaining() > 0) {
            int tag = reader.readTag();
            if (tag < 0) break;
            int fieldNum = tag >> 3;
            int wireType = tag & 7;

            if (fieldNum == 1 && wireType == 2) {
                int nodeLen = reader.readVarint32();
                byte[] nodeData = reader.readBytes(nodeLen);
                collectAliases(nodeData, aliases);
            } else if (fieldNum == 5 && wireType == 2) {
                int tensorLen = reader.readVarint32();
                byte[] tensorData = reader.readBytes(tensorLen);
                parseTensorProto(tensorData);
            } else if (fieldNum == 11 && wireType == 2) {
                // Graph input (ValueInfoProto) — just extract the name
                int len = reader.readVarint32();
                int end = reader.pos + len;
                String name = extractValueInfoName(reader, end);
                if (name != null) graphInputs.add(name);
                else reader.pos = end;
            } else if (fieldNum == 12 && wireType == 2) {
                // Graph output (ValueInfoProto)
                int len = reader.readVarint32();
                int end = reader.pos + len;
                String name = extractValueInfoName(reader, end);
                if (name != null) graphOutputs.add(name);
                else reader.pos = end;
            } else {
                reader.skipField(wireType);
            }
        }

        if (!graphInputs.isEmpty()) {
            log.info("Graph inputs: {}", graphInputs);
        }
        if (!graphOutputs.isEmpty()) {
            log.info("Graph outputs: {}", graphOutputs);
        }

        // Apply aliases: register weight matrices under canonical names and short variants
        int aliasCount = 0;
        for (String[] a : aliases) {
            String onnxName = a[0];
            String canonicalName = a[1];
            Tensor t = weights.get(onnxName);
            if (t != null) {
                if (!weights.containsKey(canonicalName)) {
                    weights.put(canonicalName, t);
                    aliasCount++;
                }
                // Also add short name without model.decoder. prefix
                if (canonicalName.startsWith("model.decoder.")) {
                    String shortName = canonicalName.substring("model.decoder.".length());
                    if (!weights.containsKey(shortName)) {
                        weights.put(shortName, t);
                        aliasCount++;
                    }
                }
                // Also add short name without model. prefix
                if (canonicalName.startsWith("model.")) {
                    String shortName = canonicalName.substring("model.".length());
                    if (!weights.containsKey(shortName)) {
                        weights.put(shortName, t);
                        aliasCount++;
                    }
                }
            }
        }
        if (aliasCount > 0) {
            log.debug("Applied {} weight aliases from graph nodes", aliasCount);
        }
    }

    // ─── NodeProto ──────────────────────────────────────────────
    // field 1: input[]     (repeated string)
    // field 3: name        (string)
    // field 4: op_type     (string)

    private void collectAliases(byte[] data, List<String[]> aliases) {
        ProtoReader reader = new ProtoReader(data);
        String opType = null;
        String nodeName = null;
        String weightName = null;
        int inputIdx = 0;

        while (reader.remaining() > 0) {
            int tag = reader.readTag();
            if (tag < 0) break;
            int fieldNum = tag >> 3;
            int wireType = tag & 7;

            if (fieldNum == 1 && wireType == 2) {
                int len = reader.readVarint32();
                String s = new String(reader.readBytes(len), java.nio.charset.StandardCharsets.UTF_8);
                if (inputIdx == 1) weightName = s;
                inputIdx++;
            } else if (fieldNum == 3 && wireType == 2) {
                int len = reader.readVarint32();
                nodeName = new String(reader.readBytes(len), java.nio.charset.StandardCharsets.UTF_8);
            } else if (fieldNum == 4 && wireType == 2) {
                int len = reader.readVarint32();
                opType = new String(reader.readBytes(len), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                reader.skipField(wireType);
            }
        }

        if (!"MatMul".equals(opType) || weightName == null || nodeName == null) return;

        // Derive canonical name from node path like /layers.N/self_attn/q_proj/MatMul
        // or /model/decoder/layers.N/self_attn/q_proj/MatMul
        String canonical = nodeNameToWeightName(nodeName);
        if (canonical != null) {
            aliases.add(new String[]{weightName, canonical});
        }
    }

    static String nodeNameToWeightName(String nodeName) {
        if (!nodeName.startsWith("/") || !nodeName.endsWith("/MatMul")) return null;
        String path = nodeName.substring(1, nodeName.length() - 7);

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String proj = path.substring(lastSlash + 1);
        String prefix = path.substring(0, lastSlash);

        StringBuilder sb = new StringBuilder();
        String[] parts = prefix.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb + "." + proj + ".weight";
    }

    private String extractValueInfoName(ProtoReader reader, int end) {
        while (reader.pos < end) {
            int tag = reader.readTag();
            if (tag < 0) break;
            int fieldNum = tag >> 3;
            int wireType = tag & 7;
            if (fieldNum == 1 && wireType == 2) {
                int len = reader.readVarint32();
                return new String(reader.readBytes(len), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                reader.skipField(wireType);
            }
        }
        return null;
    }

    // ─── TensorProto ────────────────────────────────────────────

    private void parseTensorProto(byte[] data) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        int dataType = -1;
        String name = null;
        List<Long> dims = new ArrayList<>();
        byte[] rawData = null;
        List<Float> floatData = null;
        List<Integer> int32Data = null;
        List<Long> int64Data = null;

        while (reader.remaining() > 0) {
            int tag = reader.readTag();
            if (tag < 0) break;
            int fieldNum = tag >> 3;
            int wireType = tag & 7;

            switch (fieldNum) {
                case 1: {
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        int end = reader.pos + len;
                        while (reader.pos < end) {
                            dims.add(reader.readVarint64());
                        }
                    } else if (wireType == 0) {
                        dims.add(reader.readVarint64());
                    }
                    break;
                }
                case 2:
                    if (wireType == 0) {
                        dataType = reader.readVarint32();
                    }
                    break;
                case 3:
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        reader.pos += len;
                    } else if (wireType == 0) {
                        reader.readVarint64();
                    }
                    break;
                case 4: {
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        int end = reader.pos + len;
                        floatData = new ArrayList<>();
                        while (reader.pos < end) {
                            floatData.add(Float.intBitsToFloat(reader.readFixed32()));
                        }
                    }
                    break;
                }
                case 5: {
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        int end = reader.pos + len;
                        int32Data = new ArrayList<>();
                        while (reader.pos < end) {
                            int32Data.add(reader.readVarint32());
                        }
                    }
                    break;
                }
                case 6:
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        reader.pos += len;
                    }
                    break;
                case 7: {
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        int end = reader.pos + len;
                        int64Data = new ArrayList<>();
                        while (reader.pos < end) {
                            int64Data.add(reader.readVarint64());
                        }
                    }
                    break;
                }
                case 8: {
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        name = new String(reader.readBytes(len), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    break;
                }
                case 9:
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        rawData = reader.readBytes(len);
                    }
                    break;
                case 10: {
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        reader.pos += len;
                    }
                    break;
                }
                case 11: {
                    if (wireType == 2) {
                        int len = reader.readVarint32();
                        reader.pos += len;
                    }
                    break;
                }
                case 14:
                    if (wireType == 0) {
                        reader.readVarint32();
                    }
                    break;
                default:
                    reader.skipField(wireType);
                    break;
            }
        }

        if (name == null || dataType < 0) return;

        int[] shape = new int[dims.size()];
        for (int i = 0; i < dims.size(); i++) {
            shape[i] = dims.get(i).intValue();
        }

        float[] values;
        if (rawData != null) {
            values = rawToFloats(rawData, dataType);
        } else if (floatData != null) {
            values = new float[floatData.size()];
            for (int i = 0; i < values.length; i++) values[i] = floatData.get(i);
        } else if (int32Data != null) {
            values = new float[int32Data.size()];
            for (int i = 0; i < values.length; i++) values[i] = int32Data.get(i);
        } else if (int64Data != null) {
            values = new float[int64Data.size()];
            for (int i = 0; i < values.length; i++) values[i] = int64Data.get(i);
        } else {
            return;
        }

        weights.put(name, new Tensor(values, shape));
    }

    private static float[] rawToFloats(byte[] rawData, int dataType) {
        int elemSize;
        switch (dataType) {
            case DT_FLOAT:
            case DT_INT32:
                elemSize = 4;
                break;
            case DT_INT64:
                elemSize = 8;
                break;
            case DT_UINT8:
            case DT_INT8:
                elemSize = 1;
                break;
            default:
                elemSize = 4;
        }
        int count = rawData.length / elemSize;
        float[] result = new float[count];
        ByteBuffer bb = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN);
        switch (dataType) {
            case DT_FLOAT:
                bb.asFloatBuffer().get(result);
                break;
            case DT_INT32:
                for (int i = 0; i < count; i++) result[i] = bb.getInt(i * 4);
                break;
            case DT_INT64:
                for (int i = 0; i < count; i++) result[i] = bb.getLong(i * 8);
                break;
            case DT_UINT8:
                for (int i = 0; i < count; i++) result[i] = (bb.get(i) & 0xFF);
                break;
            case DT_INT8:
                for (int i = 0; i < count; i++) result[i] = bb.get(i);
                break;
            default:
                bb.asFloatBuffer().get(result);
                break;
        }
        return result;
    }

    // ─── Minimal Protobuf Reader ────────────────────────────────

    private static class ProtoReader {
        private final byte[] buf;
        int pos;

        ProtoReader(byte[] buf) {
            this.buf = buf;
            this.pos = 0;
        }

        int remaining() {
            return buf.length - pos;
        }

        int readTag() {
            if (pos >= buf.length) return -1;
            return readVarint32();
        }

        int readVarint32() {
            int result = 0;
            int shift = 0;
            while (pos < buf.length) {
                byte b = buf[pos++];
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            return result;
        }

        long readVarint64() {
            long result = 0;
            int shift = 0;
            while (pos < buf.length) {
                byte b = buf[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            return result;
        }

        int readFixed32() {
            int result = (buf[pos] & 0xFF)
                    | ((buf[pos + 1] & 0xFF) << 8)
                    | ((buf[pos + 2] & 0xFF) << 16)
                    | ((buf[pos + 3] & 0xFF) << 24);
            pos += 4;
            return result;
        }

        byte[] readBytes(int len) {
            byte[] result = new byte[len];
            System.arraycopy(buf, pos, result, 0, len);
            pos += len;
            return result;
        }

        void skipField(int wireType) {
            switch (wireType) {
                case 0:
                    readVarint64();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 2: {
                    int len = readVarint32();
                    pos += len;
                    break;
                }
                case 5:
                    pos += 4;
                    break;
                default:
                    pos++;
                    break;
            }
        }
    }
}
