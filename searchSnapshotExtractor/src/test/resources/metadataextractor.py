import zlib, json
from newsmile import SmileDecoder

with open('meta-snapshot_v1.dat','rb') as f:
    data = f.read()

idx = data.find(b'DFL\x00')
if idx == -1:
    print('❌ DFL\\x00 marker not found')
    exit(1)

compressed = data[idx + 4:]
try:
    decompressed = zlib.decompress(compressed, -15)
    print('✅ Decompressed:', len(decompressed), 'bytes')
    try:
        print('UTF-8 content:\n', decompressed.decode('utf-8')[:1000])
    except UnicodeDecodeError:
        print('⚠️ Binary content (likely Smile). Decoding with newsmile...')
        decoded = SmileDecoder().decode(decompressed)
        print(json.dumps(decoded, indent=2))
except zlib.error as e:
    print('❌ Decompression failed:', e)
