// 生成测试用的 sample.vrma 文件
import * as fs from 'fs';
import * as path from 'path';

function makeGlb(jsonStr, binData) {
  const jsonBuf = typeof jsonStr === 'string' ? Buffer.from(jsonStr, 'utf8') : jsonStr;
  const binBuf = binData || Buffer.alloc(0);
  const jsonPad = (4 - (jsonBuf.length % 4)) % 4;
  const binPad  = (4 - (binBuf.length % 4)) % 4;
  const jsonLen = jsonBuf.length + jsonPad;
  const binLen  = binBuf.length + binPad;
  const len = 20 + jsonLen + 8 + binLen; // 12 全局头 + 8 JSON chunk 头 + jsonLen + 8 BIN chunk 头 + binLen
  const buf = Buffer.alloc(len, 0x20);  // 整个 buffer 先用空格填满
  // Global header
  buf.writeUInt32LE(0x46546C67, 0);       // magic: glTF
  buf.writeUInt32LE(2, 4);                 // version
  buf.writeUInt32LE(len, 8);               // length
  // Chunk 0: JSON
  buf.writeUInt32LE(jsonLen, 12);          // JSON chunk length
  buf.writeUInt32LE(0x4E4F534A, 16);       // "JSON"
  jsonBuf.copy(buf, 20);
  // Chunk 1: BIN
  const binHeaderOff = 20 + jsonLen;
  buf.writeUInt32LE(binLen, binHeaderOff); // BIN chunk length
  buf.writeUInt32LE(0x004E4942, binHeaderOff + 4); // "\x00BIN"
  if (binBuf.length > 0) binBuf.copy(buf, binHeaderOff + 8);
  return buf;
}

const json = JSON.stringify({
  asset: { version: '2.0' },
  extensionsUsed: ['VRMC_vrm_animation'],
  extensions: {
    VRMC_vrm_animation: {
      specVersion: '1.0',
      humanoid: { humanBones: { hips: { node: 0 } } },
    },
  },
  nodes: [{ name: 'hips' }],
  scene: 0,
  scenes: [{ nodes: [0] }],
  animations: [{
    name: 'idle',
    channels: [{ sampler: 0, target: { node: 0, path: 'rotation' } }],
    samplers: [{ input: 0, output: 1, interpolation: 'LINEAR' }],
  }],
  skin: [],
  meshes: [],
  accessors: [
    { type: 'SCALAR', componentType: 5126, count: 2, bufferView: 0 },
    { type: 'VEC4', componentType: 5126, count: 2, bufferView: 1 },
  ],
  bufferViews: [
    { buffer: 0, byteOffset: 0, byteLength: 8 },
    { buffer: 0, byteOffset: 8, byteLength: 32 },
  ],
  buffers: [{ byteLength: 40 }],
});
const bin = Buffer.alloc(40);
bin.writeFloatLE(0.0, 0);
bin.writeFloatLE(1.0, 4);
bin.writeFloatLE(0, 8); bin.writeFloatLE(0, 12); bin.writeFloatLE(0, 16); bin.writeFloatLE(1, 20);
bin.writeFloatLE(0, 24); bin.writeFloatLE(0.70710677, 28); bin.writeFloatLE(0, 32); bin.writeFloatLE(0.70710677, 36);
const glbBuf = makeGlb(json, bin);

const outputPath = path.join(process.cwd(), 'app/src/main/assets/her/sample.vrma');
fs.writeFileSync(outputPath, glbBuf);
console.log('生成测试用 sample.vrma:', outputPath, glbBuf.length, 'bytes');