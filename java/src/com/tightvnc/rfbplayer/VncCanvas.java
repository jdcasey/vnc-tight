//
//  Copyright (C) 2001,2002 HorizonLive.com, Inc.  All Rights Reserved.
//  Copyright (C) 2001 Constantin Kaplinsky.  All Rights Reserved.
//  Copyright (C) 2000 Tridia Corporation.  All Rights Reserved.
//  Copyright (C) 1999 AT&T Laboratories Cambridge.  All Rights Reserved.
//
//  This is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 2 of the License, or
//  (at your option) any later version.
//
//  This software is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this software; if not, write to the Free Software
//  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
//  USA.
//

package com.HorizonLive.RfbPlayer;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.lang.*;
import java.util.*;
import java.util.zip.*;


//
// VncCanvas is a subclass of Canvas which draws a VNC desktop on it.
//
class VncCanvas extends Canvas implements Observer {

  RfbPlayer player;
  RfbProto rfb;
  ColorModel cm24;

  Image memImage;
  Graphics memGraphics;

  Image rawPixelsImage;
  MemoryImageSource pixelsSource;
  int[] pixels24;

  // Zlib encoder's data.
  byte[] zlibBuf;
  int zlibBufLen = 0;
  Inflater zlibInflater;

  // Tight encoder's data.
  final static int tightZlibBufferSize = 512;
  Inflater[] tightInflaters;

  // Since JPEG images are loaded asynchronously, we have to remember
  // their position in the framebuffer. Also, this jpegRect object is
  // used for synchronization between the rfbThread and a JVM's thread
  // which decodes and loads JPEG images.
  Rectangle jpegRect;

  // When we're in the seeking mode, we should not update the desktop. 
  // This variable helps us to remember that repainting the desktop at
  // once is necessary when the seek operation is finished.
  boolean seekMode;

  // Distance of mouse from viewer border to trigger automatic scrolling
  final static int SCROLL_MARGIN = 50;

  // Color for border around small shared area
  static final Color DARK_GRAY = new Color(132, 138, 156);

  //
  // The constructor.
  //
  VncCanvas(RfbPlayer player) throws IOException {
    this.player = player;
    rfb = player.rfb;
    seekMode = false;

    cm24 = new DirectColorModel(24, 0xFF0000, 0x00FF00, 0x0000FF);

    updateFramebufferSize();

    setBackground(Color.white);
  }

  //
  // Callback methods to determine geometry of our Component.
  //
  public Dimension getPreferredSize() {
    Dimension d = player.desktopScrollPane.getViewportSize();
    Dimension sz = new Dimension(rfb.framebufferWidth, rfb.framebufferHeight);
    if (d.width > sz.width)
      sz.width = d.width;
    if (d.height > sz.height)
      sz.height = d.height;
    return sz;
  }

  public Dimension getMinimumSize() {
    return getPreferredSize();
  }

  public Dimension getMaximumSize() {
    return getPreferredSize();
  }

  Point getImageOrigin() {
    int x = 0, y = 0;
    Dimension d = player.desktopScrollPane.getViewportSize();
    if (rfb.framebufferWidth < d.width)
      x = d.width / 2 - rfb.framebufferWidth / 2;
    if (rfb.framebufferHeight < d.height)
      y = d.height / 2 - rfb.framebufferHeight / 2;
    return new Point(x, y);
  }

  //
  // All painting is performed here.
  //
  public void update(Graphics g) {
    paint(g);
  }

  public synchronized void paint(Graphics g) {
    Point o = getImageOrigin();
    Dimension d = getPreferredSize();
    synchronized(memImage) {
      g.drawImage(memImage, o.x, o.y, null);

      // fill in background
      if (o.x > 0 || o.y > 0) {
        // left, right, top, bottom
        Color c = g.getColor();
        g.setColor(Color.white);
        g.fillRect(0, 0, o.x, d.height);
        g.fillRect(o.x + rfb.framebufferWidth, 0, d.width - (o.x +
                   rfb.framebufferWidth), d.height);
        g.fillRect(o.x, 0, rfb.framebufferWidth, o.y);
        g.fillRect(o.x, o.y + rfb.framebufferHeight, rfb.framebufferWidth,
                   d.height - (o.y + rfb.framebufferHeight));
        g.setColor(DARK_GRAY);
        g.drawRect(o.x - 1, o.y - 1, rfb.framebufferWidth + 1,
                   rfb.framebufferHeight + 1);
        g.setColor(c);
      }
    }
    if (showSoftCursor) {
      int x0 = cursorX - hotX, y0 = cursorY - hotY;
      x0 += o.x;
      y0 += o.y;
      Rectangle r = new Rectangle(x0, y0, cursorWidth, cursorHeight);
      if (r.intersects(g.getClipBounds())) {
        Rectangle c = g.getClipBounds();
        g.setClip(o.x, o.y, rfb.framebufferWidth, rfb.framebufferHeight);
        g.drawImage(softCursor, x0, y0, null);
        g.setClip(c.x, c.y, c.width, c.height);
      }
    }
  }

  //
  // Override the ImageObserver interface method to handle drawing of
  // JPEG-encoded data.
  //
  public boolean imageUpdate(Image img, int infoflags,
                              int x, int y, int width, int height) {
    if ((infoflags & (ALLBITS | ABORT)) == 0) {
      return true;		// We need more image data.
    } else {
      // If the whole image is available, draw it now.
      if ((infoflags & ALLBITS) != 0) {
        if (jpegRect != null) {
          synchronized(jpegRect) {
            memGraphics.drawImage(img, jpegRect.x, jpegRect.y, null);
            scheduleRepaint(jpegRect.x, jpegRect.y,
                            jpegRect.width, jpegRect.height);
            jpegRect.notify();
          }
        }
      }
      return false;		// All image data was processed.
    }
  }

  void updateFramebufferSize() {

    // Useful shortcuts.
    int fbWidth = rfb.framebufferWidth;
    int fbHeight = rfb.framebufferHeight;

    // Create new off-screen image either if it does not exist, or if
    // its geometry should be changed. It's not necessary to replace
    // existing image if only pixel format should be changed.
    if (memImage == null) {
      memImage = player.createImage(fbWidth, fbHeight);
      memGraphics = memImage.getGraphics();
    } else if (memImage.getWidth(null) != fbWidth ||
        memImage.getHeight(null) != fbHeight) {
      synchronized(memImage) {
        memImage = player.createImage(fbWidth, fbHeight);
        memGraphics = memImage.getGraphics();
      }
    }

    // Images with raw pixels should be re-allocated on every change
    // of geometry or pixel format.
    pixels24 = new int[fbWidth * fbHeight];
    pixelsSource =
        new MemoryImageSource(fbWidth, fbHeight, cm24, pixels24, 0, fbWidth);
    pixelsSource.setAnimated(true);
    rawPixelsImage = createImage(pixelsSource);

    // Update the size of desktop containers unless seeking 
    if (!seekMode) {
      if (player.inSeparateFrame) {
        if (player.desktopScrollPane != null)
          resizeDesktopFrame();
      } else {
        setSize(fbWidth, fbHeight);
      }
    }
  }

  void resizeDesktopFrame() {
    // determine screen size
    Dimension screenSize = player.vncFrame.getToolkit().getScreenSize();
    Dimension scrollSize = player.desktopScrollPane.getSize();

    // Reduce Screen Size by 30 pixels in each direction;
    // This is a (poor) attempt to account for
    //     1) Menu bar on Macintosh (should really also account for
    //        Dock on OSX).  Usually 22px on top of screen.
    //     2) Taxkbar on Windows (usually about 28 px on bottom)
    //     3) Other obstructions.
    screenSize.height -= 30;
    screenSize.width -= 30;

    // Further reduce the screen size to account for the
    // scroll pane's insets.
    Insets insets = player.desktopScrollPane.getInsets();
    screenSize.height -= insets.top + insets.bottom;
    screenSize.width -= insets.left + insets.right;

    // Limit pane size to fit on screen. 
    boolean needResize = false;
    if (scrollSize.width != rfb.framebufferWidth ||
        scrollSize.height != rfb.framebufferHeight)
      needResize = true;
    int w = rfb.framebufferWidth, h = rfb.framebufferHeight;
    if (w > screenSize.width) {
      w = screenSize.width;
      needResize = true;
    }
    if (h > screenSize.height) {
      h = screenSize.height;
      needResize = true;
    }
    if (needResize)
      player.desktopScrollPane.setSize(w, h);

    // size the canvas
    setSize(getPreferredSize());

    player.vncFrame.pack();
  }

  void resizeEmbeddedApplet() {
    // resize scroll pane if necessary
    Dimension scrollSize = player.desktopScrollPane.getSize();
    if (scrollSize.width != player.dispW ||
        scrollSize.height != player.dispH) {
      player.desktopScrollPane.setSize(player.dispW, player.dispH);
    }

    player.desktopScrollPane.validate();
    // size the canvas
    setSize(getPreferredSize());
  }

  //
  // processNormalProtocol() - executed by the rfbThread to deal with
  // the RFB data.
  //
  public void processNormalProtocol() throws Exception {

    zlibInflater = new Inflater();
    tightInflaters = new Inflater[4];

    // Show current time position in the control panel.
    player.updatePos();

    // Tell our FbsInputStream object to notify us when it goes to the
    // `paused' mode.
    rfb.fbs.addObserver(this);

    // Main dispatch loop.

    while (true) {

      int msgType = rfb.readServerMessageType();

      switch (msgType) {
      case RfbProto.FramebufferUpdate:
        rfb.readFramebufferUpdate();

        for (int i = 0; i < rfb.updateNRects; i++) {
          rfb.readFramebufferUpdateRectHdr();

          boolean cursorPosReceived = false;

          int rx = rfb.updateRectX, ry = rfb.updateRectY;
          int rw = rfb.updateRectW, rh = rfb.updateRectH;

          if (rfb.updateRectEncoding == rfb.EncodingLastRect)
            break;

          if (rfb.updateRectEncoding == rfb.EncodingNewFBSize) {
            if (rfb.updateRectW != 0 && rfb.updateRectH != 0) {
              rfb.setFramebufferSize(rfb.updateRectW, rfb.updateRectH);
              updateFramebufferSize();
            }
            break;
          }

          if (rfb.updateRectEncoding == rfb.EncodingXCursor ||
              rfb.updateRectEncoding == rfb.EncodingRichCursor) {
            handleCursorShapeUpdate(rfb.updateRectEncoding, rx, ry, rw, rh);
            continue;
          }
//	  if (rfb.updateRectEncoding == rfb.EncodingXCursor ||
//	      rfb.updateRectEncoding == rfb.EncodingRichCursor) {
//	    throw new Exception("Sorry, no support for" +
//				" cursor shape updates yet");
//	  }

          if (rfb.updateRectEncoding == rfb.EncodingPointerPos) {
            softCursorMove(rx, ry);
            cursorPosReceived = true;
            continue;
          }

          switch (rfb.updateRectEncoding) {
          case RfbProto.EncodingRaw:
            handleRawRect(rx, ry, rw, rh);
            break;
          case RfbProto.EncodingCopyRect:
            handleCopyRect(rx, ry, rw, rh);
            break;
          case RfbProto.EncodingRRE:
            handleRRERect(rx, ry, rw, rh);
            break;
          case RfbProto.EncodingCoRRE:
            handleCoRRERect(rx, ry, rw, rh);
            break;
          case RfbProto.EncodingHextile:
            handleHextileRect(rx, ry, rw, rh);
            break;
          case RfbProto.EncodingZlib:
            handleZlibRect(rx, ry, rw, rh);
            break;
          case RfbProto.EncodingTight:
            handleTightRect(rx, ry, rw, rh);
            break;
          default:
            throw new Exception("Unknown RFB rectangle encoding " +
                                Integer.toString(rfb.updateRectEncoding, 16));
          }
        }
        break;

      case RfbProto.SetColourMapEntries:
        throw new Exception("Can't handle SetColourMapEntries message");

      case RfbProto.Bell:
        Toolkit.getDefaultToolkit().beep();
        break;

      case RfbProto.ServerCutText:
        String s = rfb.readServerCutText();
        break;

      default:
        throw new Exception("Unknown RFB message type " +
                            Integer.toString(msgType, 16));
      }

      player.updatePos();
    }
  }


  //
  // Handle a raw rectangle.
  //
  void handleRawRect(int x, int y, int w, int h) throws IOException {

    byte[] buf = new byte[w * 4];
    int i, offset;
    for (int dy = y; dy < y + h; dy++) {
      rfb.is.readFully(buf);
      offset = dy * rfb.framebufferWidth + x;
      for (i = 0; i < w; i++) {
        pixels24[offset + i] =
            (buf[i * 4 + 2] & 0xFF) << 16 |
            (buf[i * 4 + 1] & 0xFF) << 8 |
            (buf[i * 4] & 0xFF);
      }
    }

    handleUpdatedPixels(x, y, w, h);
    scheduleRepaint(x, y, w, h);
  }


  //
  // Handle a CopyRect rectangle.
  //
  void handleCopyRect(int x, int y, int w, int h) throws IOException {

    rfb.readCopyRect();
    memGraphics.copyArea(rfb.copyRectSrcX, rfb.copyRectSrcY, w, h,
                         x - rfb.copyRectSrcX, y - rfb.copyRectSrcY);

    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle an RRE-encoded rectangle.
  //
  void handleRRERect(int x, int y, int w, int h) throws IOException {

    int nSubrects = rfb.is.readInt();
    int sx, sy, sw, sh;

    byte[] buf = new byte[4];
    rfb.is.readFully(buf);
    Color pixel = new Color(buf[2] & 0xFF, buf[1] & 0xFF, buf[0] & 0xFF);
    memGraphics.setColor(pixel);
    memGraphics.fillRect(x, y, w, h);

    for (int j = 0; j < nSubrects; j++) {
      rfb.is.readFully(buf);
      pixel = new Color(buf[2] & 0xFF, buf[1] & 0xFF, buf[0] & 0xFF);
      sx = x + rfb.is.readUnsignedShort();
      sy = y + rfb.is.readUnsignedShort();
      sw = rfb.is.readUnsignedShort();
      sh = rfb.is.readUnsignedShort();

      memGraphics.setColor(pixel);
      memGraphics.fillRect(sx, sy, sw, sh);
    }

    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle a CoRRE-encoded rectangle.
  //
  void handleCoRRERect(int x, int y, int w, int h) throws IOException {

    int nSubrects = rfb.is.readInt();
    int sx, sy, sw, sh;

    byte[] buf = new byte[4];
    rfb.is.readFully(buf);
    Color pixel = new Color(buf[2] & 0xFF, buf[1] & 0xFF, buf[0] & 0xFF);
    memGraphics.setColor(pixel);
    memGraphics.fillRect(x, y, w, h);

    for (int j = 0; j < nSubrects; j++) {
      rfb.is.readFully(buf);
      pixel = new Color(buf[2] & 0xFF, buf[1] & 0xFF, buf[0] & 0xFF);
      sx = x + rfb.is.readUnsignedByte();
      sy = y + rfb.is.readUnsignedByte();
      sw = rfb.is.readUnsignedByte();
      sh = rfb.is.readUnsignedByte();

      memGraphics.setColor(pixel);
      memGraphics.fillRect(sx, sy, sw, sh);
    }

    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle a Hextile-encoded rectangle.
  //

  // These colors should be kept between handleHextileSubrect() calls.
  private Color hextile_bg,  hextile_fg;

  void handleHextileRect(int x, int y, int w, int h) throws IOException {

    hextile_bg = new Color(0, 0, 0);
    hextile_fg = new Color(0, 0, 0);

    for (int ty = y; ty < y + h; ty += 16) {
      int th = 16;
      if (y + h - ty < 16)
        th = y + h - ty;

      for (int tx = x; tx < x + w; tx += 16) {
        int tw = 16;
        if (x + w - tx < 16)
          tw = x + w - tx;

        handleHextileSubrect(tx, ty, tw, th);
      }

      // Finished with a row of tiles, now let's show it.
      scheduleRepaint(x, y, w, h);
    }
  }

  //
  // Handle one tile in the Hextile-encoded data.
  //
  void handleHextileSubrect(int tx, int ty, int tw, int th)
      throws IOException {

    byte[] buf = new byte[256 * 4];

    int subencoding = rfb.is.readUnsignedByte();

    // Is it a raw-encoded sub-rectangle?
    if ((subencoding & rfb.HextileRaw) != 0) {
      int count, offset;
      for (int j = ty; j < ty + th; j++) {
        rfb.is.readFully(buf, 0, tw * 4);
        offset = j * rfb.framebufferWidth + tx;
        for (count = 0; count < tw; count++) {
          pixels24[offset + count] =
              (buf[count * 4 + 2] & 0xFF) << 16 |
              (buf[count * 4 + 1] & 0xFF) << 8 |
              (buf[count * 4] & 0xFF);
        }
      }
      handleUpdatedPixels(tx, ty, tw, th);
      return;
    }

    // Read and draw the background if specified.
    if ((subencoding & rfb.HextileBackgroundSpecified) != 0) {
      rfb.is.readFully(buf, 0, 4);
      hextile_bg = new Color(buf[2] & 0xFF, buf[1] & 0xFF, buf[0] & 0xFF);
    }
    memGraphics.setColor(hextile_bg);
    memGraphics.fillRect(tx, ty, tw, th);

    // Read the foreground color if specified.
    if ((subencoding & rfb.HextileForegroundSpecified) != 0) {
      rfb.is.readFully(buf, 0, 4);
      hextile_fg = new Color(buf[2] & 0xFF, buf[1] & 0xFF, buf[0] & 0xFF);
    }

    // Done with this tile if there is no sub-rectangles.
    if ((subencoding & rfb.HextileAnySubrects) == 0)
      return;

    int nSubrects = rfb.is.readUnsignedByte();

    int b1, b2, sx, sy, sw, sh;
    if ((subencoding & rfb.HextileSubrectsColoured) != 0) {
      for (int j = 0; j < nSubrects; j++) {
        rfb.is.readFully(buf, 0, 4);
        hextile_fg = new Color(buf[2] & 0xFF, buf[1] & 0xFF, buf[0] & 0xFF);
        b1 = rfb.is.readUnsignedByte();
        b2 = rfb.is.readUnsignedByte();
        sx = tx + (b1 >> 4);
        sy = ty + (b1 & 0xf);
        sw = (b2 >> 4) + 1;
        sh = (b2 & 0xf) + 1;
        memGraphics.setColor(hextile_fg);
        memGraphics.fillRect(sx, sy, sw, sh);
      }
    } else {
      memGraphics.setColor(hextile_fg);
      for (int j = 0; j < nSubrects; j++) {
        b1 = rfb.is.readUnsignedByte();
        b2 = rfb.is.readUnsignedByte();
        sx = tx + (b1 >> 4);
        sy = ty + (b1 & 0xf);
        sw = (b2 >> 4) + 1;
        sh = (b2 & 0xf) + 1;
        memGraphics.fillRect(sx, sy, sw, sh);
      }
    }
  }

  //
  // Handle a Zlib-encoded rectangle.
  //
  void handleZlibRect(int x, int y, int w, int h) throws Exception {

    int nBytes = rfb.is.readInt();

    if (zlibBuf == null || zlibBufLen < nBytes) {
      zlibBufLen = nBytes * 2;
      zlibBuf = new byte[zlibBufLen];
    }

    rfb.is.readFully(zlibBuf, 0, nBytes);
    zlibInflater.setInput(zlibBuf, 0, nBytes);

    try {
      byte[] buf = new byte[w * 4];
      int i, offset;
      for (int dy = y; dy < y + h; dy++) {
        zlibInflater.inflate(buf);
        offset = dy * rfb.framebufferWidth + x;
        for (i = 0; i < w; i++) {
          pixels24[offset + i] =
              (buf[i * 4 + 2] & 0xFF) << 16 |
              (buf[i * 4 + 1] & 0xFF) << 8 |
              (buf[i * 4] & 0xFF);
        }
      }
    } catch (DataFormatException dfe) {
      throw new Exception(dfe.toString());
    }

    handleUpdatedPixels(x, y, w, h);
    scheduleRepaint(x, y, w, h);
  }

  //
  // Handle a Tight-encoded rectangle.
  //
  void handleTightRect(int x, int y, int w, int h) throws Exception {

    int comp_ctl = rfb.is.readUnsignedByte();

    // Flush zlib streams if we are told by the server to do so.
    for (int stream_id = 0; stream_id < 4; stream_id++) {
      if ((comp_ctl & 1) != 0 && tightInflaters[stream_id] != null) {
        tightInflaters[stream_id] = null;
      }
      comp_ctl >>= 1;
    }

    // Check correctness of subencoding value.
    if (comp_ctl > rfb.TightMaxSubencoding) {
      throw new Exception("Incorrect tight subencoding: " + comp_ctl);
    }

    // Handle solid-color rectangles.
    if (comp_ctl == rfb.TightFill) {
      byte[] buf = new byte[3];
      rfb.is.readFully(buf);
      Color bg = new Color(buf[0] & 0xFF, buf[1] & 0xFF, buf[2] & 0xFF);
      memGraphics.setColor(bg);
      memGraphics.fillRect(x, y, w, h);
      scheduleRepaint(x, y, w, h);
      return;
    }

    if (comp_ctl == rfb.TightJpeg) {

      // Read JPEG data.
      byte[] jpegData = new byte[rfb.readCompactLen()];
      rfb.is.readFully(jpegData);

      // Create an Image object from the JPEG data.
      Image jpegImage = Toolkit.getDefaultToolkit().createImage(jpegData);

      // Remember the rectangle where the image should be drawn.
      jpegRect = new Rectangle(x, y, w, h);

      // Let the imageUpdate() method do the actual drawing, here just
      // wait until the image is fully loaded and drawn.
      synchronized(jpegRect) {
        Toolkit.getDefaultToolkit().prepareImage(jpegImage, -1, -1, this);
        try {
          // Wait no longer than three seconds.
          jpegRect.wait(3000);
        } catch (InterruptedException e) {
          throw new Exception("Interrupted while decoding JPEG image");
        }
      }

      // Done, jpegRect is not needed any more.
      jpegRect = null;
      return;

    }

    // Read filter id and parameters.
    int numColors = 0, rowSize = w;
    byte[] palette8 = new byte[2];
    int[] palette24 = new int[256];
    boolean useGradient = false;
    if ((comp_ctl & rfb.TightExplicitFilter) != 0) {
      int filter_id = rfb.is.readUnsignedByte();
      if (filter_id == rfb.TightFilterPalette) {
        numColors = rfb.is.readUnsignedByte() + 1;
        byte[] buf = new byte[numColors * 3];
        rfb.is.readFully(buf);
        for (int i = 0; i < numColors; i++) {
          palette24[i] = ((buf[i * 3] & 0xFF) << 16 |
              (buf[i * 3 + 1] & 0xFF) << 8 |
              (buf[i * 3 + 2] & 0xFF));
        }
        if (numColors == 2)
          rowSize = (w + 7) / 8;
      } else if (filter_id == rfb.TightFilterGradient) {
        useGradient = true;
      } else if (filter_id != rfb.TightFilterCopy) {
        throw new Exception("Incorrect tight filter id: " + filter_id);
      }
    }
    if (numColors == 0)
      rowSize *= 3;

    // Read, optionally uncompress and decode data.
    int dataSize = h * rowSize;
    if (dataSize < rfb.TightMinToCompress) {
      // Data size is small - not compressed with zlib.
      if (numColors != 0) {
        // Indexed colors.
        byte[] indexedData = new byte[dataSize];
        rfb.is.readFully(indexedData);
        if (numColors == 2) {
          // Two colors.
          decodeMonoData(x, y, w, h, indexedData, palette24);
        } else {
          // 3..255 colors.
          int i = 0;
          for (int dy = y; dy < y + h; dy++) {
            for (int dx = x; dx < x + w; dx++) {
              pixels24[dy * rfb.framebufferWidth + dx] =
                  palette24[indexedData[i++] & 0xFF];
            }
          }
        }
      } else if (useGradient) {
        // "Gradient"-processed data
        byte[] buf = new byte[w * h * 3];
        rfb.is.readFully(buf);
        decodeGradientData(x, y, w, h, buf);
      } else {
        // Raw truecolor data.
        byte[] buf = new byte[w * 3];
        int i, offset;
        for (int dy = y; dy < y + h; dy++) {
          rfb.is.readFully(buf);
          offset = dy * rfb.framebufferWidth + x;
          for (i = 0; i < w; i++) {
            pixels24[offset + i] =
                (buf[i * 3] & 0xFF) << 16 |
                (buf[i * 3 + 1] & 0xFF) << 8 |
                (buf[i * 3 + 2] & 0xFF);
          }
        }
      }
    } else {
      // Data was compressed with zlib.
      int zlibDataLen = rfb.readCompactLen();
      byte[] zlibData = new byte[zlibDataLen];
      rfb.is.readFully(zlibData);
      int stream_id = comp_ctl & 0x03;
      if (tightInflaters[stream_id] == null) {
        tightInflaters[stream_id] = new Inflater();
      }
      Inflater myInflater = tightInflaters[stream_id];
      myInflater.setInput(zlibData);
      try {
        if (numColors != 0) {
          // Indexed colors.
          byte[] indexedData = new byte[dataSize];
          myInflater.inflate(indexedData);
          if (numColors == 2) {
            // Two colors.
            decodeMonoData(x, y, w, h, indexedData, palette24);
          } else {
            // More than two colors.
            int i = 0;
            for (int dy = y; dy < y + h; dy++) {
              for (int dx = x; dx < x + w; dx++) {
                pixels24[dy * rfb.framebufferWidth + dx] =
                    palette24[indexedData[i++] & 0xFF];
              }
            }
          }
        } else if (useGradient) {
          // Compressed "Gradient"-filtered data.
          byte[] buf = new byte[w * h * 3];
          myInflater.inflate(buf);
          decodeGradientData(x, y, w, h, buf);
        } else {
          // Compressed truecolor data.
          byte[] buf = new byte[w * 3];
          int i, offset;
          for (int dy = y; dy < y + h; dy++) {
            myInflater.inflate(buf);
            offset = dy * rfb.framebufferWidth + x;
            for (i = 0; i < w; i++) {
              pixels24[offset + i] =
                  (buf[i * 3] & 0xFF) << 16 |
                  (buf[i * 3 + 1] & 0xFF) << 8 |
                  (buf[i * 3 + 2] & 0xFF);
            }
          }
        }
      } catch (DataFormatException dfe) {
        throw new Exception(dfe.toString());
      }
    }

    handleUpdatedPixels(x, y, w, h);
    scheduleRepaint(x, y, w, h);
  }

  //
  // Decode 1bpp-encoded bi-color rectangle.
  //
  void decodeMonoData(int x, int y, int w, int h, byte[] src, int[] palette) {

    int dx, dy, n;
    int i = y * rfb.framebufferWidth + x;
    int rowBytes = (w + 7) / 8;
    byte b;

    for (dy = 0; dy < h; dy++) {
      for (dx = 0; dx < w / 8; dx++) {
        b = src[dy * rowBytes + dx];
        for (n = 7; n >= 0; n--)
          pixels24[i++] = palette[b >> n & 1];
      }
      for (n = 7; n >= 8 - w % 8; n--) {
        pixels24[i++] = palette[src[dy * rowBytes + dx] >> n & 1];
      }
      i += (rfb.framebufferWidth - w);
    }
  }

  //
  // Decode data processed with the "Gradient" filter.
  //
  void decodeGradientData(int x, int y, int w, int h, byte[] buf) {

    int dx, dy, c;
    byte[] prevRow = new byte[w * 3];
    byte[] thisRow = new byte[w * 3];
    byte[] pix = new byte[3];
    int[] est = new int[3];

    int offset = y * rfb.framebufferWidth + x;

    for (dy = 0; dy < h; dy++) {

      /* First pixel in a row */
      for (c = 0; c < 3; c++) {
        pix[c] = (byte)(prevRow[c] + buf[dy * w * 3 + c]);
        thisRow[c] = pix[c];
      }
      pixels24[offset++] =
          (pix[0] & 0xFF) << 16 | (pix[1] & 0xFF) << 8 | (pix[2] & 0xFF);

      /* Remaining pixels of a row */
      for (dx = 1; dx < w; dx++) {
        for (c = 0; c < 3; c++) {
          est[c] = ((prevRow[dx * 3 + c] & 0xFF) + (pix[c] & 0xFF) -
              (prevRow[(dx - 1) * 3 + c] & 0xFF));
          if (est[c] > 0xFF) {
            est[c] = 0xFF;
          } else if (est[c] < 0x00) {
            est[c] = 0x00;
          }
          pix[c] = (byte)(est[c] + buf[(dy * w + dx) * 3 + c]);
          thisRow[dx * 3 + c] = pix[c];
        }
        pixels24[offset++] =
            (pix[0] & 0xFF) << 16 | (pix[1] & 0xFF) << 8 | (pix[2] & 0xFF);
      }

      System.arraycopy(thisRow, 0, prevRow, 0, w * 3);
      offset += (rfb.framebufferWidth - w);
    }
  }


  //
  // Display newly updated area of pixels.
  //
  void handleUpdatedPixels(int x, int y, int w, int h) {

    // Draw updated pixels of the off-screen image.

    pixelsSource.newPixels(x, y, w, h);
    memGraphics.setClip(x, y, w, h);
    memGraphics.drawImage(rawPixelsImage, 0, 0, null);
    memGraphics.setClip(0, 0, rfb.framebufferWidth, rfb.framebufferHeight);
  }

  //////////////////////////////////////////////////////////////////
  //
  // Handle cursor shape updates (XCursor and RichCursor encodings).
  //
  boolean showSoftCursor = false;

  int[] softCursorPixels;
  MemoryImageSource softCursorSource;
  Image softCursor;

  int cursorX = 0, cursorY = 0;
  int cursorWidth, cursorHeight;
  int origCursorWidth, origCursorHeight;
  int hotX, hotY;
  int origHotX, origHotY;
  int deferCursorUpdates = 10;

  //
  // Handle cursor shape update (XCursor and RichCursor encodings).
  //
  synchronized void handleCursorShapeUpdate(int encodingType,
                                             int xhot, int yhot, int width,
                                             int height)
      throws IOException {

    int bytesPerRow = (width + 7) / 8;
    int bytesMaskData = bytesPerRow * height;

    softCursorFree();

    if (width * height == 0)
      return;

//    // Ignore cursor shape data if requested by user.
//
//    if (viewer.options.ignoreCursorUpdates) {
//      if (encodingType == rfb.EncodingXCursor) {
//	rfb.is.skipBytes(6 + bytesMaskData * 2);
//      } else {
//	// rfb.EncodingRichCursor
//	rfb.is.skipBytes(width * height + bytesMaskData);
//      }
//      return;
//    }

    // Decode cursor pixel data.

    softCursorPixels = new int[width * height];

    if (encodingType == rfb.EncodingXCursor) {

      // Read foreground and background colors of the cursor.
      byte[] rgb = new byte[6];
      rfb.is.readFully(rgb);
      int[] colors = {(0xFF000000 | (rgb[3] & 0xFF) << 16 |
                      (rgb[4] & 0xFF) << 8 | (rgb[5] & 0xFF)),
                      (0xFF000000 | (rgb[0] & 0xFF) << 16 |
                      (rgb[1] & 0xFF) << 8 | (rgb[2] & 0xFF))
      };

      // Read pixel and mask data.
      byte[] pixBuf = new byte[bytesMaskData];
      rfb.is.readFully(pixBuf);
      byte[] maskBuf = new byte[bytesMaskData];
      rfb.is.readFully(maskBuf);

      // Decode pixel data into softCursorPixels[].
      byte pixByte, maskByte;
      int x, y, n, result;
      int i = 0;
      for (y = 0; y < height; y++) {
        for (x = 0; x < width / 8; x++) {
          pixByte = pixBuf[y * bytesPerRow + x];
          maskByte = maskBuf[y * bytesPerRow + x];
          for (n = 7; n >= 0; n--) {
            if ((maskByte >> n & 1) != 0) {
              result = colors[pixByte >> n & 1];
            } else {
              result = 0;	// Transparent pixel
            }
            softCursorPixels[i++] = result;
          }
        }
        for (n = 7; n >= 8 - width % 8; n--) {
          if ((maskBuf[y * bytesPerRow + x] >> n & 1) != 0) {
            result = colors[pixBuf[y * bytesPerRow + x] >> n & 1];
          } else {
            result = 0;		// Transparent pixel
          }
          softCursorPixels[i++] = result;
        }
      }

    } else {
      // encodingType == rfb.EncodingRichCursor

      // Read pixel and mask data.
      byte[] pixBuf = new byte[width * height * 4];
      rfb.is.readFully(pixBuf);
      byte[] maskBuf = new byte[bytesMaskData];
      rfb.is.readFully(maskBuf);

      // Decode pixel data into softCursorPixels[].
      byte pixByte, maskByte;
      int x, y, n, result;
      int i = 0;
      for (y = 0; y < height; y++) {
        for (x = 0; x < width / 8; x++) {
          maskByte = maskBuf[y * bytesPerRow + x];
          for (n = 7; n >= 0; n--) {
            if ((maskByte >> n & 1) != 0) {
//	      if (bytesPerPixel == 1) {
//		result = cm8.getRGB(pixBuf[i]);
//	      } else {
              result = (pixBuf[i * 4] & 0xFF) << 24 |
                  (pixBuf[i * 4 + 1] & 0xFF) << 16 |
                  (pixBuf[i * 4 + 2] & 0xFF) << 8 |
                  (pixBuf[i * 4 + 3] & 0xFF);
            //result = 0xFF000000 |
            // (pixBuf[i * 4 + 1] & 0xFF) << 16 |
            // (pixBuf[i * 4 + 2] & 0xFF) << 8 |
            // (pixBuf[i * 4 + 3] & 0xFF);
//	      }
            } else {
              result = 0;	// Transparent pixel
            }
            softCursorPixels[i++] = result;
          }
        }

        for (n = 7; n >= 8 - width % 8; n--) {
          if ((maskBuf[y * bytesPerRow + x] >> n & 1) != 0) {
//	    if (bytesPerPixel == 1) {
//	      result = cm8.getRGB(pixBuf[i]);
//	    } else {
            result = 0xFF000000 |
                (pixBuf[i * 4 + 1] & 0xFF) << 16 |
                (pixBuf[i * 4 + 2] & 0xFF) << 8 |
                (pixBuf[i * 4 + 3] & 0xFF);
//	    }
          } else {
            result = 0;		// Transparent pixel
          }
          softCursorPixels[i++] = result;
        }
      }

    }

    // Draw the cursor on an off-screen image.

    softCursorSource =
        new MemoryImageSource(width, height, softCursorPixels, 0, width);
    softCursor = Toolkit.getDefaultToolkit().createImage(softCursorSource);
//    if (inputEnabled && viewer.options.scaleCursor != 0) {
//	int w = (width * viewer.options.scaleCursor) / 100;
//	int h = (height * viewer.options.scaleCursor) / 100;
//	Image newCursor = softCursor.getScaledInstance(w, h, Image.SCALE_SMOOTH);
//	softCursor = newCursor;
//	cursorWidth = w;
//	cursorHeight = h;
//	hotX = (xhot * viewer.options.scaleCursor) / 100;
//	hotY = (yhot * viewer.options.scaleCursor) / 100;
//    } else {
    cursorWidth = width;
    cursorHeight = height;
    hotX = xhot;
    hotY = yhot;
//    }

    // Set data associated with cursor.
    origCursorWidth = width;
    origCursorHeight = height;
    origHotX = xhot;
    origHotY = yhot;

    showSoftCursor = true;

    // Show the cursor.
    scheduleRepaint(cursorX - hotX, cursorY - hotY, cursorWidth, cursorHeight);
  }

  private void scrollToPoint(int x, int y) {
    boolean needScroll = false;

    if (player.desktopScrollPane == null)
      return;

    Dimension d = player.desktopScrollPane.getSize();
    Point topLeft = player.desktopScrollPane.getScrollPosition();
    Point botRight = new Point(topLeft.x + d.width, topLeft.y + d.height);

    if (x < topLeft.x + SCROLL_MARGIN) {
      // shift left
      topLeft.x = x - SCROLL_MARGIN;
      needScroll = true;
    } else if (x > botRight.x - SCROLL_MARGIN) {
      // shift right
      topLeft.x = x - d.width + SCROLL_MARGIN;
      needScroll = true;
    }
    if (y < topLeft.y + SCROLL_MARGIN) {
      // shift up
      topLeft.y = y - SCROLL_MARGIN;
      needScroll = true;
    } else if (y > botRight.y - SCROLL_MARGIN) {
      // shift down
      topLeft.y = y - d.height + SCROLL_MARGIN;
      needScroll = true;
    }
    player.desktopScrollPane.setScrollPosition(topLeft.x, topLeft.y);
  }

  //
  // softCursorMove(). Moves soft cursor into a particular location.
  //
  synchronized void softCursorMove(int x, int y) {
    Point o = getImageOrigin();
    int oldX = cursorX + o.x;
    int oldY = cursorY + o.y;
    cursorX = x;
    cursorY = y;

    // paint and scroll 
    if (showSoftCursor) {
      scheduleRepaint(oldX - hotX, oldY - hotY, cursorWidth, cursorHeight);
      scheduleRepaint(cursorX - hotX + o.x, cursorY - hotY + o.y, cursorWidth,
                      cursorHeight);
      if (!seekMode)
        scrollToPoint(x, y);
    }

    cursorX = x;
    cursorY = y;
  }
  //
  // softCursorFree(). Remove soft cursor, dispose resources.
  //
  synchronized void softCursorFree() {
    if (showSoftCursor) {
      showSoftCursor = false;
      softCursor = null;
      softCursorSource = null;
      softCursorPixels = null;

      Point o = getImageOrigin();
      scheduleRepaint(cursorX - hotX + o.x, cursorY - hotY + o.y, cursorWidth,
                      cursorHeight);
    }
  }
  //
  // Tell JVM to repaint specified desktop area.
  //
  void scheduleRepaint(int x, int y, int w, int h) {
    if (rfb.fbs.isSeeking()) {
      // Do nothing, and remember we are seeking.
      seekMode = true;
    } else {
      if (seekMode) {
        // Immediate repaint of the whole desktop after seeking.
        seekMode = false;
        if (showSoftCursor)
          scrollToPoint(cursorX, cursorY);
        updateFramebufferSize();
        repaint();
      } else {
        // Usual incremental repaint.
        Point o = getImageOrigin();
        repaint(player.deferScreenUpdates, o.x + x, o.y + y, w, h);
      }
    }
  }

  //
  // We are observing our FbsInputStream object to get notified on
  // switching to the `paused' mode. In such cases we want to repaint
  // our desktop if we were seeking.
  //
  public void update(Observable o, Object arg) {
    // Immediate repaint of the whole desktop after seeking.
    repaint();
    // Let next scheduleRepaint() call invoke incremental drawing.
    seekMode = false;
  }

}
