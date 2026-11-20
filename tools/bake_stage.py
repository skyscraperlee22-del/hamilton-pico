"""Bake fixed stage lights into linear vertex colors; preserve the original triangles."""
import json
import math
import struct
import sys
from pathlib import Path

source, target = map(Path, sys.argv[1:])
raw = source.read_bytes()
json_size = struct.unpack_from('<I', raw, 12)[0]
scene = json.loads(raw[20:20 + json_size])
binary = memoryview(raw)[28 + json_size:]
vertices = bytearray()
indices = bytearray()
lookup = {}
bounds = [[math.inf] * 3, [-math.inf] * 3]

def triples(index):
    accessor = scene['accessors'][index]
    view = scene['bufferViews'][accessor['bufferView']]
    start = view.get('byteOffset', 0) + accessor.get('byteOffset', 0)
    return struct.iter_unpack('<3f', binary[start:start + accessor['count'] * 12])

for node in scene['nodes']:
    assert set(node) <= {'mesh', 'name'}
    for prim in scene['meshes'][node['mesh']]['primitives']:
        assert 'indices' not in prim and prim.get('mode', 4) == 4
        attrs = prim['attributes']
        base = scene['materials'][prim['material']]['pbrMetallicRoughness']['baseColorFactor']
        for pos, normal in zip(triples(attrs['POSITION']), triples(attrs['NORMAL'])):
            x, y, z = pos
            nx, ny, nz = normal
            key = max(0, -.32*nx + .82*ny + .47*nz)
            fill = .60 + 1.25*key
            for lamp_x in (-4.5, 4.5):
                dx, dy, dz = lamp_x-x, 8.3-y, .2-z
                dist = max(.01, math.sqrt(dx*dx + dy*dy + dz*dz))
                incidence = max(0, (dx*nx + dy*ny + dz*nz)/dist)
                beam = max(0, (dy*.95 - dz*.31)/dist)
                fill += .75*incidence*beam**6/(1+dist*dist/90)
            color = [round(255*min(1,base[i]*fill*tint)) for i,tint in enumerate((1,.90,.78))]
            vertex = struct.pack('<3f4B', *pos, *color, 255)
            idx = lookup.get(vertex)
            if idx is None:
                idx = len(lookup)
                lookup[vertex] = idx
                vertices.extend(vertex)
                for i,v in enumerate(pos):
                    bounds[0][i] = min(bounds[0][i], v)
                    bounds[1][i] = max(bounds[1][i], v)
            indices.extend(struct.pack('<H', idx))

data = bytes(vertices + indices)
asset = {
    'asset': {'version':'2.0'}, 'scene':0, 'scenes':[{'nodes':[0]}],
    'nodes':[{'name':'BakedTheater', 'mesh':0}],
    'meshes':[{'primitives':[{'attributes':{'POSITION':0,'COLOR_0':1},'indices':2,'material':0}]}],
    'materials':[{'name':'BakedStage','pbrMetallicRoughness':{'metallicFactor':0},
                  'doubleSided':True,'extensions':{'KHR_materials_unlit':{}}}],
    'extensionsUsed':['KHR_materials_unlit'],
    'buffers':[{'byteLength':len(data)}],
    'bufferViews':[{'buffer':0,'byteLength':len(vertices),'byteStride':16,'target':34962},
                   {'buffer':0,'byteOffset':len(vertices),'byteLength':len(indices),'target':34963}],
    'accessors':[{'bufferView':0,'componentType':5126,'count':len(lookup),'type':'VEC3','min':bounds[0],'max':bounds[1]},
                 {'bufferView':0,'byteOffset':12,'componentType':5121,'normalized':True,'count':len(lookup),'type':'VEC4'},
                 {'bufferView':1,'componentType':5123,'count':len(indices)//2,'type':'SCALAR'}]
}
def chunk(payload, kind, padding):
    payload += padding*((-len(payload))%4)
    return struct.pack('<I4s',len(payload),kind) + payload
chunks = chunk(json.dumps(asset,separators=(',',':')).encode(), b'JSON', b' ') + chunk(data,b'BIN\0',b'\0')
target.write_bytes(struct.pack('<4sII',b'glTF',2,12+len(chunks))+chunks)
