// Rebuild the bundled asset with esbuild: browser platform, IIFE format, ES2020.
// Dependencies: hyparquet 1.32.0 and hyparquet-compressors 1.1.3.
import { parquetMetadataAsync, parquetReadObjects } from "hyparquet";
import { compressors } from "hyparquet-compressors";

const columns = ["lot_code", "wafer_code", "row", "col", "status"];
const limit = 512;

window.readWafer = async function readWafer(encoded) {
  try {
    const file = Uint8Array.from(atob(encoded), char => char.charCodeAt(0)).buffer;
    const metadata = await parquetMetadataAsync(file);
    const count = Number(metadata.num_rows);
    if (count < 1 || count > limit * limit) throw new Error();
    for (const group of metadata.row_groups) {
      if (Number(group.total_byte_size) > 24 * 1024 * 1024) throw new Error();
    }
    const records = await parquetReadObjects({ file, metadata, compressors, columns, rowEnd: count });
    if (records.length !== count) throw new Error();
    const lot = records[0].lot_code;
    const wafer = records[0].wafer_code;
    if (typeof lot !== "string" || typeof wafer !== "string" || lot.length > 128 || wafer.length > 128) {
      throw new Error();
    }

    const cells = new Uint8Array(limit * limit);
    let height = 0;
    let width = 0;
    for (const record of records) {
      if (record.lot_code !== lot || record.wafer_code !== wafer) throw new Error();
      const row = integer(record.row);
      const col = integer(record.col);
      const status = integer(record.status);
      if (row < 1 || row > limit || col < 1 || col > limit || status < 0 || status > 2) throw new Error();
      const index = (row - 1) * limit + col - 1;
      if (cells[index] !== 0) throw new Error();
      cells[index] = status + 1;
      height = Math.max(height, row);
      width = Math.max(width, col);
    }
    const map = Array.from({ length: height }, (_, row) =>
      Array.from({ length: width }, (_, col) => Math.max(0, cells[row * limit + col] - 1)));
    WaferResult.complete(JSON.stringify({ lot_code: lot, wafer_code: wafer, map }));
  } catch {
    WaferResult.complete("");
  }
};

function integer(value) {
  if (typeof value !== "number" && typeof value !== "bigint") throw new Error();
  const number = Number(value);
  if (!Number.isSafeInteger(number)) throw new Error();
  return number;
}
