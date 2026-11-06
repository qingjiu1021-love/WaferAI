import { parquetWriteBuffer } from 'hyparquet-writer'

globalThis.writeWafer = function (encoded) {
  try {
    const input = Uint8Array.from(atob(encoded), char => char.charCodeAt(0))
    const wafer = JSON.parse(new TextDecoder().decode(input))
    const rows = []
    const columns = []
    const statuses = []
    for (const [row, bins] of wafer.map.entries()) {
      for (const [col, status] of bins.entries()) {
        if (status === 0 && (row !== wafer.map.length - 1 || col !== bins.length - 1)) continue
        rows.push(row + 1)
        columns.push(col + 1)
        statuses.push(status)
      }
    }
    const data = new Uint8Array(parquetWriteBuffer({
      columnData: [
        { name: 'lot_code', data: Array(rows.length).fill(wafer.lot_code), type: 'STRING' },
        { name: 'wafer_code', data: Array(rows.length).fill(wafer.wafer_code), type: 'STRING' },
        { name: 'row', data: rows, type: 'INT32' },
        { name: 'col', data: columns, type: 'INT32' },
        { name: 'status', data: statuses, type: 'INT32' },
      ],
      codec: 'SNAPPY',
      rowGroupSize: 8192,
      statistics: false,
    }))
    const chunks = []
    for (let offset = 0; offset < data.length; offset += 8192) {
      chunks.push(String.fromCharCode(...data.subarray(offset, offset + 8192)))
    }
    WaferResult.complete(btoa(chunks.join('')))
  } catch {
    WaferResult.complete('')
  }
}
