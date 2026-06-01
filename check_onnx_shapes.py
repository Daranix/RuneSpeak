import onnx
import numpy as np
from onnx import numpy_helper

base = 'F:/runespeak_cache/models--onnx-community--opus-mt-en-es/snapshots/b5eba94a023a1954c90401b43537f479f962981d/onnx'
m = onnx.load(f'{base}/decoder_model.onnx')

# Find layer 0 nodes
for node in m.graph.node:
    if 'layers.0' in node.name:
        print(f'Node: {node.name}')
        print(f'  OpType: {node.op_type}')
        for i, inp in enumerate(node.input):
            shape_info = ''
            for vi in m.graph.value_info:
                if vi.name == inp:
                    shape = tuple(d.dim_value for d in vi.type.tensor_type.shape.dim)
                    shape_info = f'  shape={shape}'
            # Also check initializers
            for init in m.graph.initializer:
                if init.name == inp:
                    arr = numpy_helper.to_array(init)
                    shape_info = f'  shape={arr.shape}  dtype={arr.dtype}'
            print(f'  Input[{i}]: {inp}{shape_info}')
        print()
