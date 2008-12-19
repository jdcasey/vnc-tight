package com.tightvnc.decoder;

import com.tightvnc.vncviewer.RfbInputStream;
import java.awt.Graphics;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

//
// Class that used for decoding ZLib encoded data.
//

public class ZlibDecoder extends RawDecoder {

  public ZlibDecoder(Graphics g, RfbInputStream is) {
    super(g, is);
  }

  public ZlibDecoder(Graphics g, RfbInputStream is, int frameBufferW,
                     int frameBufferH) {
    super(g, is, frameBufferW, frameBufferH);
  }

  //
  // Override handleRect method to decode ZLib encoded data insted of
  // raw pixel data.
  //

  public void handleRect(int x, int y, int w, int h) throws IOException  {
    int nBytes = rfbis.readU32();

    if (zlibBuf == null || zlibBufLen < nBytes) {
      zlibBufLen = nBytes * 2;
      zlibBuf = new byte[zlibBufLen];
    }

    rfbis.readFully(zlibBuf, 0, nBytes);

    //
    // Save decoded data to RecordInterface
    //

    if (rec.canWrite() && rec.isRecordFromBeginning()) {
      rec.writeIntBE(nBytes);
      rec.write(zlibBuf, 0, nBytes);
    }

    if (zlibInflater == null) {
      zlibInflater = new Inflater();
    }
    zlibInflater.setInput(zlibBuf, 0, nBytes);

    try {
      if (bytesPerPixel == 1) {
        for (int dy = y; dy < y + h; dy++) {
          zlibInflater.inflate(pixels8, dy * framebufferWidth + x, w);

          //
          // Save decoded data to RecordInterface
          //

          if (rec.canWrite() && !rec.isRecordFromBeginning())
            rec.write(pixels8, dy * framebufferWidth + x, w);
        }
      } else {
        byte[] buf = new byte[w * 4];
        int i, offset;
        for (int dy = y; dy < y + h; dy++) {
          zlibInflater.inflate(buf);
          offset = dy * framebufferWidth + x;
          for (i = 0; i < w; i++) {
            RawDecoder.pixels24[offset + i] =
              (buf[i * 4 + 2] & 0xFF) << 16 |
              (buf[i * 4 + 1] & 0xFF) << 8 |
              (buf[i * 4] & 0xFF);
          }

          //
          // Save decoded data to RecordInterface
          //

          if (rec.canWrite() && !rec.isRecordFromBeginning())
            rec.write(buf);
        }
      }
    } catch (DataFormatException ex) {
      ex.printStackTrace();
    }
    handleUpdatedPixels(x, y, w, h);
  }

  //
  // Zlib encoder's data.
  //

  protected byte[] zlibBuf;
  protected int zlibBufLen = 0;
  protected Inflater zlibInflater;
}
