package com.runespeak.translate.tensor;

import java.util.Arrays;

public class Tensor {
    private final float[] data;
    private final int[] shape;
    private final int[] strides;

    public Tensor(float[] data, int[] shape) {
        int expected = 1;
        for (int s : shape) expected *= s;
        if (data.length != expected) {
            throw new IllegalArgumentException("Data length " + data.length + " does not match shape " + Arrays.toString(shape) + " (" + expected + ")");
        }
        this.data = data;
        this.shape = shape.clone();
        this.strides = computeStrides(shape);
    }

    public Tensor(int[] shape) {
        int size = 1;
        for (int s : shape) size *= s;
        this.data = new float[size];
        this.shape = shape.clone();
        this.strides = computeStrides(shape);
    }

    private static int[] computeStrides(int[] shape) {
        int[] s = new int[shape.length];
        int stride = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            s[i] = stride;
            stride *= shape[i];
        }
        return s;
    }

    public int[] shape() { return shape.clone(); }
    public int dims() { return shape.length; }
    public float[] data() { return data; }
    public int size() { return data.length; }

    public int stride(int dim) { return strides[dim]; }

    public int index(int... indices) {
        int idx = 0;
        for (int i = 0; i < indices.length; i++) {
            idx += indices[i] * strides[i];
        }
        return idx;
    }

    public float get(int... indices) {
        return data[index(indices)];
    }

    public void set(float val, int... indices) {
        data[index(indices)] = val;
    }

    public float getFlat(int i) { return data[i]; }
    public void setFlat(int i, float v) { data[i] = v; }

    public Tensor slice(int dim, int index) {
        int[] newShape = new int[shape.length - 1];
        int newIdx = 0;
        for (int i = 0; i < shape.length; i++) {
            if (i != dim) newShape[newIdx++] = shape[i];
        }
        int sliceSize = 1;
        for (int s : newShape) sliceSize *= s;
        float[] newData = new float[sliceSize];
        int srcStride = strides[dim];
        int srcStart = index * srcStride;
        int[] newStrides = computeStrides(newShape);
        for (int i = 0; i < sliceSize; i++) {
            int srcIdx = srcStart;
            int tmp = i;
            for (int j = newShape.length - 1; j >= 0; j--) {
                srcIdx += (tmp / newStrides[j]) * strides[j < dim ? j : j + 1];
                tmp %= newStrides[j];
            }
            newData[i] = data[srcIdx];
        }
        return new Tensor(newData, newShape);
    }

    public Tensor transpose() {
        if (shape.length != 2) throw new UnsupportedOperationException("Transpose only for 2D");
        float[] t = new float[data.length];
        int rows = shape[0], cols = shape[1];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                t[j * rows + i] = data[i * cols + j];
            }
        }
        return new Tensor(t, new int[]{cols, rows});
    }

    public Tensor add(Tensor other) {
        if (data.length != other.data.length) throw new IllegalArgumentException("Size mismatch");
        float[] r = new float[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] + other.data[i];
        return new Tensor(r, shape);
    }

    public Tensor mul(Tensor other) {
        if (data.length != other.data.length) throw new IllegalArgumentException("Size mismatch");
        float[] r = new float[data.length];
        for (int i = 0; i < data.length; i++) r[i] = data[i] * other.data[i];
        return new Tensor(r, shape);
    }

    public Tensor matmul(Tensor other) {
        if (shape.length != 2 || other.shape.length != 2) throw new UnsupportedOperationException("matmul requires 2D tensors");
        if (shape[1] != other.shape[0]) throw new IllegalArgumentException("Matrix dim mismatch: " + shape[1] + " vs " + other.shape[0]);
        int m = shape[0], n = shape[1], p = other.shape[1];
        float[] r = new float[m * p];
        for (int i = 0; i < m; i++) {
            int rowBase = i * n;
            int rBase = i * p;
            for (int j = 0; j < p; j++) {
                float sum = 0;
                for (int k = 0; k < n; k++) {
                    sum += data[rowBase + k] * other.data[k * p + j];
                }
                r[rBase + j] = sum;
            }
        }
        return new Tensor(r, new int[]{m, p});
    }

    public static Tensor softmax(Tensor t, int dim) {
        int outer = 1, inner = 1, dimSize = t.shape[dim];
        for (int i = 0; i < dim; i++) outer *= t.shape[i];
        for (int i = dim + 1; i < t.shape.length; i++) inner *= t.shape[i];
        float[] r = new float[t.data.length];
        for (int o = 0; o < outer; o++) {
            for (int i = 0; i < inner; i++) {
                int base = o * dimSize * inner + i;
                float max = Float.NEGATIVE_INFINITY;
                for (int d = 0; d < dimSize; d++) {
                    float v = t.data[base + d * inner];
                    if (v > max) max = v;
                }
                float sum = 0;
                for (int d = 0; d < dimSize; d++) {
                    float v = (float) Math.exp(t.data[base + d * inner] - max);
                    r[base + d * inner] = v;
                    sum += v;
                }
                for (int d = 0; d < dimSize; d++) {
                    r[base + d * inner] /= sum;
                }
            }
        }
        return new Tensor(r, t.shape);
    }

    public Tensor unsqueeze(int dim) {
        int[] newShape = new int[shape.length + 1];
        int idx = 0;
        for (int i = 0; i < newShape.length; i++) {
            if (i == dim) {
                newShape[i] = 1;
            } else {
                newShape[i] = shape[idx++];
            }
        }
        return new Tensor(data.clone(), newShape);
    }

    public Tensor copy() {
        return new Tensor(data.clone(), shape);
    }

    public void fill(float v) {
        Arrays.fill(data, v);
    }

    @Override
    public String toString() {
        return "Tensor(shape=" + Arrays.toString(shape) + ")";
    }
}
