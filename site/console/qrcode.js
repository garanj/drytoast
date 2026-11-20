/**
 * Zero-dependency ISO/IEC 18004 QR Code SVG generator (Byte mode, ECC Level L, Versions 1..14).
 * Renders crisp monochrome SVG QR codes locally in the browser with zero external requests.
 */
(function (global) {
  "use strict";

  // Total codewords and ECC codewords per block for ECC Level L, Versions 1..14
  const VERSION_TABLE = [
    null,
    { total: 26, ecPerBlock: 7, blocks: [1, 19] },
    { total: 44, ecPerBlock: 10, blocks: [1, 34] },
    { total: 70, ecPerBlock: 15, blocks: [1, 55] },
    { total: 100, ecPerBlock: 20, blocks: [1, 80] },
    { total: 134, ecPerBlock: 26, blocks: [1, 108] },
    { total: 172, ecPerBlock: 18, blocks: [2, 68] },
    { total: 196, ecPerBlock: 20, blocks: [2, 78] },
    { total: 242, ecPerBlock: 24, blocks: [2, 97] },
    { total: 292, ecPerBlock: 30, blocks: [2, 116] },
    { total: 346, ecPerBlock: 18, blocks: [2, 68, 2, 69] },
    { total: 404, ecPerBlock: 20, blocks: [4, 81] },
    { total: 466, ecPerBlock: 24, blocks: [2, 92, 2, 93] },
    { total: 532, ecPerBlock: 26, blocks: [4, 107] },
    { total: 581, ecPerBlock: 30, blocks: [3, 115, 1, 116] }
  ];

  const ALIGNMENT_PATTERNS = [
    null,
    [],
    [6, 18],
    [6, 22],
    [6, 26],
    [6, 30],
    [6, 34],
    [6, 22, 38],
    [6, 24, 42],
    [6, 26, 46],
    [6, 28, 50],
    [6, 30, 54],
    [6, 32, 58],
    [6, 34, 62],
    [6, 26, 46, 66]
  ];

  // GF(256) exponent and log tables
  const EXP = new Uint8Array(512);
  const LOG = new Uint8Array(256);
  (function initGalois() {
    let x = 1;
    for (let i = 0; i < 255; i++) {
      EXP[i] = x;
      LOG[x] = i;
      x <<= 1;
      if (x & 0x100) x ^= 0x11d;
    }
    for (let i = 255; i < 512; i++) {
      EXP[i] = EXP[i - 255];
    }
  })();

  function gfMul(a, b) {
    return a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]];
  }

  function rsGeneratorPoly(degree) {
    let poly = new Uint8Array([1]);
    for (let i = 0; i < degree; i++) {
      const next = new Uint8Array(poly.length + 1);
      const factor = EXP[i];
      for (let j = 0; j < poly.length; j++) {
        next[j] ^= poly[j];
        next[j + 1] ^= gfMul(poly[j], factor);
      }
      poly = next;
    }
    return poly;
  }

  function rsEncode(data, ecLen) {
    const gen = rsGeneratorPoly(ecLen);
    const rem = new Uint8Array(ecLen);
    for (let i = 0; i < data.length; i++) {
      const factor = data[i] ^ rem[0];
      for (let j = 0; j < ecLen - 1; j++) {
        rem[j] = rem[j + 1] ^ gfMul(gen[j + 1], factor);
      }
      rem[ecLen - 1] = gfMul(gen[ecLen], factor);
    }
    return rem;
  }

  function selectVersion(byteLength) {
    for (let v = 1; v < VERSION_TABLE.length; v++) {
      const info = VERSION_TABLE[v];
      let dataCapacity = 0;
      for (let i = 0; i < info.blocks.length; i += 2) {
        dataCapacity += info.blocks[i] * info.blocks[i + 1];
      }
      const headerBits = v <= 9 ? 12 : 20;
      const maxBytes = Math.floor((dataCapacity * 8 - headerBits) / 8);
      if (byteLength <= maxBytes) return { version: v, dataCapacity };
    }
    throw new Error("QR payload exceeds Version 14 capacity");
  }

  function buildDataCodewords(bytes, version, dataCapacity) {
    const bits = [];
    function pushBits(val, len) {
      for (let i = len - 1; i >= 0; i--) {
        bits.push((val >>> i) & 1);
      }
    }
    // Byte mode indicator (0100)
    pushBits(0b0100, 4);
    pushBits(bytes.length, version <= 9 ? 8 : 16);
    for (let i = 0; i < bytes.length; i++) {
      pushBits(bytes[i], 8);
    }
    const maxBits = dataCapacity * 8;
    const terminator = Math.min(4, maxBits - bits.length);
    pushBits(0, terminator);
    while (bits.length % 8 !== 0) bits.push(0);

    const codewords = new Uint8Array(dataCapacity);
    let idx = 0;
    for (let i = 0; i < bits.length; i += 8) {
      let b = 0;
      for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
      codewords[idx++] = b;
    }
    const padBytes = [0xec, 0x11];
    let padIdx = 0;
    while (idx < dataCapacity) {
      codewords[idx++] = padBytes[padIdx % 2];
      padIdx++;
    }
    return codewords;
  }

  function interleaveBlocks(dataCodewords, version) {
    const info = VERSION_TABLE[version];
    const dataBlocks = [];
    const ecBlocks = [];
    let offset = 0;
    for (let i = 0; i < info.blocks.length; i += 2) {
      const count = info.blocks[i];
      const len = info.blocks[i + 1];
      for (let b = 0; b < count; b++) {
        const slice = dataCodewords.slice(offset, offset + len);
        offset += len;
        dataBlocks.push(slice);
        ecBlocks.push(rsEncode(slice, info.ecPerBlock));
      }
    }
    const result = [];
    const maxDataLen = Math.max(...dataBlocks.map((b) => b.length));
    for (let i = 0; i < maxDataLen; i++) {
      for (let b = 0; b < dataBlocks.length; b++) {
        if (i < dataBlocks[b].length) result.push(dataBlocks[b][i]);
      }
    }
    for (let i = 0; i < info.ecPerBlock; i++) {
      for (let b = 0; b < ecBlocks.length; b++) {
        result.push(ecBlocks[b][i]);
      }
    }
    return result;
  }

  function createMatrix(version, codewords) {
    const size = version * 4 + 17;
    const modules = Array.from({ length: size }, () => new Int8Array(size));
    const isFunction = Array.from({ length: size }, () => new Uint8Array(size));

    function setFunctionModule(r, c, dark) {
      if (r < 0 || r >= size || c < 0 || c >= size) return;
      modules[r][c] = dark ? 1 : 0;
      isFunction[r][c] = 1;
    }

    function drawFinder(r, c) {
      for (let dy = -1; dy <= 7; dy++) {
        for (let dx = -1; dx <= 7; dx++) {
          const dist = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
          const dark = dist !== 2 && dist !== 4;
          setFunctionModule(r + dy, c + dx, dark);
        }
      }
    }

    drawFinder(0, 0);
    drawFinder(0, size - 7);
    drawFinder(size - 7, 0);

    for (let i = 8; i < size - 8; i++) {
      setFunctionModule(6, i, i % 2 === 0);
      setFunctionModule(i, 6, i % 2 === 0);
    }

    const align = ALIGNMENT_PATTERNS[version];
    for (let i = 0; i < align.length; i++) {
      for (let j = 0; j < align.length; j++) {
        if (
          (i === 0 && j === 0) ||
          (i === 0 && j === align.length - 1) ||
          (i === align.length - 1 && j === 0)
        ) {
          continue;
        }
        const cr = align[i];
        const cc = align[j];
        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) {
            setFunctionModule(cr + dy, cc + dx, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
          }
        }
      }
    }

    // Reserve format info areas
    for (let i = 0; i <= 8; i++) {
      if (i !== 6) {
        setFunctionModule(8, i, false);
        setFunctionModule(i, 8, false);
      }
    }
    for (let i = 0; i < 8; i++) {
      setFunctionModule(8, size - 1 - i, false);
      setFunctionModule(size - 1 - i, 8, false);
    }
    setFunctionModule(size - 8, 8, true);

    // Reserve version info for version >= 7
    if (version >= 7) {
      let rem = version;
      for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      const vBits = (version << 12) | rem;
      for (let i = 0; i < 18; i++) {
        const dark = ((vBits >>> i) & 1) !== 0;
        const a = size - 11 + (i % 3);
        const b = Math.floor(i / 3);
        setFunctionModule(a, b, dark);
        setFunctionModule(b, a, dark);
      }
    }

    // Place data bits in zig-zag
    let bitIdx = 0;
    const totalBits = codewords.length * 8;
    let upward = true;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) {
        const r = upward ? size - 1 - vert : vert;
        for (let j = 0; j < 2; j++) {
          const c = right - j;
          if (!isFunction[r][c]) {
            let dark = false;
            if (bitIdx < totalBits) {
              dark = ((codewords[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) !== 0;
              bitIdx++;
            }
            // Mask 0: (r + c) % 2 === 0
            if ((r + c) % 2 === 0) dark = !dark;
            modules[r][c] = dark ? 1 : 0;
          }
        }
      }
      upward = !upward;
    }

    // Write Format Info for ECC Level L (01) and Mask 0 (000) -> data = 0b01000
    let fData = 0b01000;
    let fRem = fData;
    for (let i = 0; i < 10; i++) fRem = (fRem << 1) ^ ((fRem >>> 9) * 0x537);
    const fBits = ((fData << 10) | fRem) ^ 0x5412;

    for (let i = 0; i <= 5; i++) setFunctionModule(i, 8, ((fBits >>> i) & 1) !== 0);
    setFunctionModule(7, 8, ((fBits >>> 6) & 1) !== 0);
    setFunctionModule(8, 8, ((fBits >>> 7) & 1) !== 0);
    setFunctionModule(8, 7, ((fBits >>> 8) & 1) !== 0);
    for (let i = 9; i < 15; i++) setFunctionModule(8, 14 - i, ((fBits >>> i) & 1) !== 0);

    for (let i = 0; i < 8; i++) setFunctionModule(8, size - 1 - i, ((fBits >>> i) & 1) !== 0);
    for (let i = 8; i < 15; i++) setFunctionModule(size - 15 + i, 8, ((fBits >>> i) & 1) !== 0);

    return { size, modules };
  }

  function renderQrSvg(text, margin = 3) {
    const bytes = new TextEncoder().encode(text);
    const { version, dataCapacity } = selectVersion(bytes.length);
    const dataCw = buildDataCodewords(bytes, version, dataCapacity);
    const allCw = interleaveBlocks(dataCw, version);
    const { size, modules } = createMatrix(version, allCw);
    const total = size + margin * 2;

    let path = "";
    for (let r = 0; r < size; r++) {
      for (let c = 0; c < size; c++) {
        if (modules[r][c]) {
          path += `M${c + margin},${r + margin}h1v1h-1z`;
        }
      }
    }

    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${total}" shape-rendering="crispEdges" role="img" aria-label="QR Code"><rect width="${total}" height="${total}" fill="#ffffff"/><path d="${path}" fill="#000000"/></svg>`;
  }

  global.ThresholdQr = { renderQrSvg };
})(window);
