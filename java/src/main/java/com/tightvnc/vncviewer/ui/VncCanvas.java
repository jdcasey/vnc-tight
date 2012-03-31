//
//  Copyright (C) 2004 Horizon Wimba.  All Rights Reserved.
//  Copyright (C) 2001-2003 HorizonLive.com, Inc.  All Rights Reserved.
//  Copyright (C) 2001,2002 Constantin Kaplinsky.  All Rights Reserved.
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

package com.tightvnc.vncviewer.ui;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.MemoryImageSource;
import java.io.IOException;

import com.tightvnc.decoder.CoRREDecoder;
import com.tightvnc.decoder.CopyRectDecoder;
import com.tightvnc.decoder.HextileDecoder;
import com.tightvnc.decoder.RREDecoder;
import com.tightvnc.decoder.RawDecoder;
import com.tightvnc.decoder.TightDecoder;
import com.tightvnc.decoder.ZRLEDecoder;
import com.tightvnc.decoder.ZlibDecoder;
import com.tightvnc.decoder.common.Repaintable;
import com.tightvnc.vncviewer.VncViewer;
import com.tightvnc.vncviewer.io.RecordOutputStream;
import com.tightvnc.vncviewer.io.RfbInputStream;
import com.tightvnc.vncviewer.proto.RfbProto;

//
// VncCanvas is a subclass of Canvas which draws a VNC desktop on it.
//

public class VncCanvas
    extends Canvas
    implements KeyListener, MouseListener, MouseMotionListener, Repaintable, Runnable
{

    private static final long serialVersionUID = 1L;

    VncViewer viewer;

    RfbProto rfb;

    ColorModel cm8, cm24;

    int bytesPixel;

    int maxWidth = 0, maxHeight = 0;

    int scalingFactor;

    int scaledWidth, scaledHeight;

    Image memImage;

    Graphics memGraphics;

    //
    // Decoders
    //

    RawDecoder rawDecoder;

    RREDecoder rreDecoder;

    CoRREDecoder correDecoder;

    ZlibDecoder zlibDecoder;

    HextileDecoder hextileDecoder;

    ZRLEDecoder zrleDecoder;

    TightDecoder tightDecoder;

    CopyRectDecoder copyRectDecoder;

    // Base decoder decoders array
    RawDecoder[] decoders = null;

    // Update statistics.
    private long statStartTime; // time on first framebufferUpdateRequest

    private long statNumUpdates; // counter for FramebufferUpdate messages

    private long statNumTotalRects; // rectangles in FramebufferUpdate messages

    private long statNumPixelRects; // the same, but excluding pseudo-rectangles

    private long statNumRectsTight; // Tight-encoded rectangles (including JPEG)

    private long statNumRectsTightJPEG; // JPEG-compressed Tight-encoded rectangles

    private long statNumRectsZRLE; // ZRLE-encoded rectangles

    private long statNumRectsHextile; // Hextile-encoded rectangles

    private long statNumRectsRaw; // Raw-encoded rectangles

    private long statNumRectsCopy; // CopyRect rectangles

    private long statNumBytesEncoded; // number of bytes in updates, as received

    private long statNumBytesDecoded; // number of bytes, as if Raw encoding was used

    // True if we process keyboard and mouse events.
    boolean inputEnabled;

    // True if was no one auto resize of canvas
    private boolean isFirstSizeAutoUpdate = true;

    // Members for limiting sending mouse events to server
    long lastMouseEventSendTime = System.currentTimeMillis();

    long mouseMaxFreq = 20;

    //
    // The constructors.
    //

    public VncCanvas( final VncViewer v, final int maxWidth_, final int maxHeight_ )
        throws IOException
    {

        viewer = v;
        maxWidth = maxWidth_;
        maxHeight = maxHeight_;

        rfb = viewer.getProtocol();
        scalingFactor = viewer.getOptions()
                              .getScalingFactor();

        cm8 = new DirectColorModel( 8, 7, ( 7 << 3 ), ( 3 << 6 ) );
        cm24 = new DirectColorModel( 24, 0xFF0000, 0x00FF00, 0x0000FF );

        //
        // Create decoders
        //

        // Input stream for decoders
        final RfbInputStream rfbis = new RfbInputStream( rfb );
        // Create output stream for session recording
        final RecordOutputStream ros = new RecordOutputStream( rfb );

        rawDecoder = new RawDecoder( memGraphics, rfbis );
        rreDecoder = new RREDecoder( memGraphics, rfbis );
        correDecoder = new CoRREDecoder( memGraphics, rfbis );
        hextileDecoder = new HextileDecoder( memGraphics, rfbis );
        tightDecoder = new TightDecoder( memGraphics, rfbis );
        zlibDecoder = new ZlibDecoder( memGraphics, rfbis );
        zrleDecoder = new ZRLEDecoder( memGraphics, rfbis );
        copyRectDecoder = new CopyRectDecoder( memGraphics, rfbis );

        //
        // Set data for decoders that needs extra parameters
        //

        hextileDecoder.setRepainableControl( this );
        tightDecoder.setRepainableControl( this );

        //
        // Create array that contains our decoders
        //

        decoders = new RawDecoder[8];
        decoders[0] = rawDecoder;
        decoders[1] = rreDecoder;
        decoders[2] = correDecoder;
        decoders[3] = hextileDecoder;
        decoders[4] = zlibDecoder;
        decoders[5] = tightDecoder;
        decoders[6] = zrleDecoder;
        decoders[7] = copyRectDecoder;

        //
        // Set session recorder for decoders
        //

        for ( final RawDecoder decoder : decoders )
        {
            decoder.setDataOutputStream( ros );
        }

        setPixelFormat();
        resetSelection();

        inputEnabled = false;
        if ( !viewer.getOptions()
                    .isViewOnly() )
        {
            enableInput( true );
        }

        // Enable mouse and keyboard event listeners.
        addKeyListener( this );
        addMouseListener( this );
        addMouseMotionListener( this );

        // Create thread, that will send mouse movement events
        // to VNC server.
        final Thread mouseThread = new Thread( this );
        mouseThread.start();
    }

    public VncCanvas( final VncViewer v )
        throws IOException
    {
        this( v, 0, 0 );
    }

    //
    // Callback methods to determine geometry of our Component.
    //

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension( scaledWidth, scaledHeight );
    }

    @Override
    public Dimension getMinimumSize()
    {
        return new Dimension( scaledWidth, scaledHeight );
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension( scaledWidth, scaledHeight );
    }

    //
    // All painting is performed here.
    //

    @Override
    public void update( final Graphics g )
    {
        paint( g );
    }

    @Override
    public void paint( final Graphics g )
    {
        synchronized ( memImage )
        {
            if ( rfb.getFramebufferWidth() == scaledWidth )
            {
                g.drawImage( memImage, 0, 0, null );
            }
            else
            {
                paintScaledFrameBuffer( g );
            }
        }
        if ( showSoftCursor )
        {
            final int x0 = cursorX - hotX, y0 = cursorY - hotY;
            final Rectangle r = new Rectangle( x0, y0, cursorWidth, cursorHeight );
            if ( r.intersects( g.getClipBounds() ) )
            {
                g.drawImage( softCursor, x0, y0, null );
            }
        }
        if ( isInSelectionMode() )
        {
            final Rectangle r = getSelection( true );
            if ( r.width > 0 && r.height > 0 )
            {
                // Don't forget to correct the coordinates for the right and bottom
                // borders, so that the borders are the part of the selection.
                r.width -= 1;
                r.height -= 1;
                g.setXORMode( Color.yellow );
                g.drawRect( r.x, r.y, r.width, r.height );
            }
        }
    }

    public void paintScaledFrameBuffer( final Graphics g )
    {
        g.drawImage( memImage, 0, 0, scaledWidth, scaledHeight, null );
    }

    //
    // Start/stop receiving mouse events. Keyboard events are received
    // even in view-only mode, because we want to map the 'r' key to the
    // screen refreshing function.
    //

    public synchronized void enableInput( final boolean enable )
    {
        if ( enable && !inputEnabled )
        {
            inputEnabled = true;
            if ( viewer.isShowControls() )
            {
                viewer.getButtonPanel()
                      .enableRemoteAccessControls( true );
            }
            createSoftCursor(); // scaled cursor
        }
        else if ( !enable && inputEnabled )
        {
            inputEnabled = false;
            if ( viewer.isShowControls() )
            {
                viewer.getButtonPanel()
                      .enableRemoteAccessControls( false );
            }
            createSoftCursor(); // non-scaled cursor
        }
    }

    public void setPixelFormat()
        throws IOException
    {
        if ( viewer.getOptions()
                   .isEightBitColors() )
        {
            rfb.writeSetPixelFormat( 8, 8, false, true, 7, 7, 3, 0, 3, 6 );
            bytesPixel = 1;
        }
        else
        {
            rfb.writeSetPixelFormat( 32, 24, false, true, 255, 255, 255, 16, 8, 0 );
            bytesPixel = 4;
        }
        updateFramebufferSize();
    }

    void setScalingFactor( final int sf )
    {
        scalingFactor = sf;
        updateFramebufferSize();
        invalidate();
    }

    public void updateFramebufferSize()
    {

        // Useful shortcuts.
        final int fbWidth = rfb.getFramebufferWidth();
        final int fbHeight = rfb.getFramebufferHeight();

        // FIXME: This part of code must be in VncViewer i think
        if ( viewer.getOptions()
                   .isAutoScale() )
        {
            if ( viewer.isInAnApplet() )
            {
                maxWidth = viewer.getWidth();
                maxHeight = viewer.getHeight();
            }
            else
            {
                if ( viewer.getVncFrame() != null )
                {
                    if ( isFirstSizeAutoUpdate() )
                    {
                        setFirstSizeAutoUpdate( false );
                        final Dimension screenSize = viewer.getVncFrame()
                                                           .getToolkit()
                                                           .getScreenSize();
                        maxWidth = (int) screenSize.getWidth() - 100;
                        maxHeight = (int) screenSize.getHeight() - 100;
                        viewer.getVncFrame()
                              .setSize( maxWidth, maxHeight );
                    }
                    else
                    {
                        viewer.getDesktopScrollPane()
                              .doLayout();
                        maxWidth = viewer.getDesktopScrollPane()
                                         .getWidth();
                        maxHeight = viewer.getDesktopScrollPane()
                                          .getHeight();
                    }
                }
                else
                {
                    maxWidth = fbWidth;
                    maxHeight = fbHeight;
                }
            }
            final int f1 = maxWidth * 100 / fbWidth;
            final int f2 = maxHeight * 100 / fbHeight;
            scalingFactor = Math.min( f1, f2 );
            if ( scalingFactor > 100 )
            {
                scalingFactor = 100;
            }
            System.out.println( "Scaling desktop at " + scalingFactor + "%" );
        }

        // Update scaled framebuffer geometry.
        scaledWidth = ( fbWidth * scalingFactor + 50 ) / 100;
        scaledHeight = ( fbHeight * scalingFactor + 50 ) / 100;

        // Create new off-screen image either if it does not exist, or if
        // its geometry should be changed. It's not necessary to replace
        // existing image if only pixel format should be changed.
        if ( memImage == null )
        {
            memImage = viewer.getVncContainer()
                             .createImage( fbWidth, fbHeight );
            memGraphics = memImage.getGraphics();
        }
        else if ( memImage.getWidth( null ) != fbWidth || memImage.getHeight( null ) != fbHeight )
        {
            synchronized ( memImage )
            {
                memImage = viewer.getVncContainer()
                                 .createImage( fbWidth, fbHeight );
                memGraphics = memImage.getGraphics();
            }
        }

        //
        // Update decoders
        //

        //
        // FIXME: Why decoders can be null here?
        //

        if ( decoders != null )
        {
            for ( final RawDecoder decoder : decoders )
            {
                //
                // Set changes to every decoder that we can use
                //

                decoder.setBPP( bytesPixel );
                decoder.setFrameBufferSize( fbWidth, fbHeight );
                decoder.setGraphics( memGraphics );

                //
                // Update decoder
                //

                decoder.update();
            }
        }

        // FIXME: This part of code must be in VncViewer i think
        // Update the size of desktop containers.
        if ( viewer.isInSeparateFrame() )
        {
            if ( viewer.getDesktopScrollPane() != null )
            {
                if ( !viewer.getOptions()
                            .isAutoScale() )
                {
                    resizeDesktopFrame();
                }
                else
                {
                    setSize( scaledWidth, scaledHeight );
                    viewer.getDesktopScrollPane()
                          .setSize( maxWidth + 200, maxHeight + 200 );
                }
            }
        }
        else
        {
            setSize( scaledWidth, scaledHeight );
        }
        viewer.moveFocusToDesktop();
    }

    public void resizeDesktopFrame()
    {
        setSize( scaledWidth, scaledHeight );

        // FIXME: Find a better way to determine correct size of a
        // ScrollPane. -- const
        final Insets insets = viewer.getDesktopScrollPane()
                                    .getInsets();
        viewer.getDesktopScrollPane()
              .setSize( scaledWidth + 2 * Math.min( insets.left, insets.right ),
                        scaledHeight + 2 * Math.min( insets.top, insets.bottom ) );

        viewer.getVncFrame()
              .pack();

        // Try to limit the frame size to the screen size.

        final Dimension screenSize = viewer.getVncFrame()
                                           .getToolkit()
                                           .getScreenSize();
        final Dimension frameSize = viewer.getVncFrame()
                                          .getSize();
        final Dimension newSize = frameSize;

        // Reduce Screen Size by 30 pixels in each direction;
        // This is a (poor) attempt to account for
        // 1) Menu bar on Macintosh (should really also account for
        // Dock on OSX). Usually 22px on top of screen.
        // 2) Taxkbar on Windows (usually about 28 px on bottom)
        // 3) Other obstructions.

        screenSize.height -= 30;
        screenSize.width -= 30;

        boolean needToResizeFrame = false;
        if ( frameSize.height > screenSize.height )
        {
            newSize.height = screenSize.height;
            needToResizeFrame = true;
        }
        if ( frameSize.width > screenSize.width )
        {
            newSize.width = screenSize.width;
            needToResizeFrame = true;
        }
        if ( needToResizeFrame )
        {
            viewer.getVncFrame()
                  .setSize( newSize );
        }

        viewer.getDesktopScrollPane()
              .doLayout();
    }

    //
    // processNormalProtocol() - executed by the rfbThread to deal with the
    // RFB socket.
    //

    public void processNormalProtocol()
        throws Exception
    {

        // Start/stop session recording if necessary.
        viewer.checkRecordingStatus();

        rfb.writeFramebufferUpdateRequest( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight(), false );

        if ( viewer.getOptions()
                   .isContinuousUpdates() )
        {
            rfb.tryEnableContinuousUpdates( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight() );
        }

        resetStats();
        boolean statsRestarted = false;

        //
        // main dispatch loop
        //

        while ( true )
        {

            // Read message type from the server.
            final int msgType = rfb.readServerMessageType();

            // Process the message depending on its type.
            switch ( msgType )
            {
                case RfbProto.FramebufferUpdate:

                    if ( getStatNumUpdates() == viewer.getDebugStatsExcludeUpdates() && !statsRestarted )
                    {
                        resetStats();
                        statsRestarted = true;
                    }
                    else if ( getStatNumUpdates() == viewer.getDebugStatsMeasureUpdates() && statsRestarted )
                    {
                        viewer.disconnect();
                    }

                    rfb.readFramebufferUpdate();
                    setStatNumUpdates( getStatNumUpdates() + 1 );

                    boolean cursorPosReceived = false;

                    for ( int i = 0; i < rfb.getUpdateNRects(); i++ )
                    {

                        rfb.readFramebufferUpdateRectHdr();
                        setStatNumTotalRects( getStatNumTotalRects() + 1 );
                        final int rx = rfb.getUpdateRectX(), ry = rfb.getUpdateRectY();
                        final int rw = rfb.getUpdateRectW(), rh = rfb.getUpdateRectH();

                        if ( rfb.getUpdateRectEncoding() == RfbProto.EncodingLastRect )
                        {
                            break;
                        }

                        if ( rfb.getUpdateRectEncoding() == RfbProto.EncodingNewFBSize )
                        {
                            rfb.setFramebufferSize( rw, rh );
                            updateFramebufferSize();
                            break;
                        }

                        if ( rfb.getUpdateRectEncoding() == RfbProto.EncodingXCursor
                            || rfb.getUpdateRectEncoding() == RfbProto.EncodingRichCursor )
                        {
                            handleCursorShapeUpdate( rfb.getUpdateRectEncoding(), rx, ry, rw, rh );
                            continue;
                        }

                        if ( rfb.getUpdateRectEncoding() == RfbProto.EncodingPointerPos )
                        {
                            softCursorMove( rx, ry );
                            cursorPosReceived = true;
                            continue;
                        }

                        final long numBytesReadBefore = rfb.getNumBytesRead();

                        rfb.startTiming();

                        switch ( rfb.getUpdateRectEncoding() )
                        {
                            case RfbProto.EncodingRaw:
                                setStatNumRectsRaw( getStatNumRectsRaw() + 1 );
                                handleRawRect( rx, ry, rw, rh );
                                break;
                            case RfbProto.EncodingCopyRect:
                                setStatNumRectsCopy( getStatNumRectsCopy() + 1 );
                                handleCopyRect( rx, ry, rw, rh );
                                break;
                            case RfbProto.EncodingRRE:
                                handleRRERect( rx, ry, rw, rh );
                                break;
                            case RfbProto.EncodingCoRRE:
                                handleCoRRERect( rx, ry, rw, rh );
                                break;
                            case RfbProto.EncodingHextile:
                                setStatNumRectsHextile( getStatNumRectsHextile() + 1 );
                                handleHextileRect( rx, ry, rw, rh );
                                break;
                            case RfbProto.EncodingZRLE:
                                setStatNumRectsZRLE( getStatNumRectsZRLE() + 1 );
                                handleZRLERect( rx, ry, rw, rh );
                                break;
                            case RfbProto.EncodingZlib:
                                handleZlibRect( rx, ry, rw, rh );
                                break;
                            case RfbProto.EncodingTight:
                                if ( tightDecoder != null )
                                {
                                    setStatNumRectsTightJPEG( tightDecoder.getNumJPEGRects() );
                                    // statNumRectsTight = tightDecoder.getNumTightRects();
                                }
                                setStatNumRectsTight( getStatNumRectsTight() + 1 );
                                handleTightRect( rx, ry, rw, rh );
                                break;
                            default:
                                throw new Exception( "Unknown RFB rectangle encoding " + rfb.getUpdateRectEncoding() );
                        }

                        rfb.stopTiming();

                        setStatNumPixelRects( getStatNumPixelRects() + 1 );
                        setStatNumBytesDecoded( getStatNumBytesDecoded() + rw * rh * bytesPixel );
                        setStatNumBytesEncoded( getStatNumBytesEncoded()
                            + (int) ( rfb.getNumBytesRead() - numBytesReadBefore ) );
                    }

                    boolean fullUpdateNeeded = false;

                    // Start/stop session recording if necessary. Request full
                    // update if a new session file was opened.
                    if ( viewer.checkRecordingStatus() )
                    {
                        fullUpdateNeeded = true;
                    }

                    // Defer framebuffer update request if necessary. But wake up
                    // immediately on keyboard or mouse event. Also, don't sleep
                    // if there is some data to receive, or if the last update
                    // included a PointerPos message.
                    if ( viewer.getDeferUpdateRequests() > 0 && rfb.available() == 0 && !cursorPosReceived )
                    {
                        synchronized ( rfb )
                        {
                            try
                            {
                                rfb.wait( viewer.getDeferUpdateRequests() );
                            }
                            catch ( final InterruptedException e )
                            {
                            }
                        }
                    }

                    viewer.autoSelectEncodings();

                    // Before requesting framebuffer update, check if the pixel
                    // format should be changed.
                    if ( viewer.getOptions()
                               .isEightBitColors() != ( bytesPixel == 1 ) )
                    {
                        // Pixel format should be changed.
                        if ( !rfb.continuousUpdatesAreActive() )
                        {
                            // Continuous updates are not used. In this case, we just
                            // set new pixel format and request full update.
                            setPixelFormat();
                            fullUpdateNeeded = true;
                        }
                        else
                        {
                            // Otherwise, disable continuous updates first. Pixel
                            // format will be set later when we are sure that there
                            // will be no unsolicited framebuffer updates.
                            rfb.tryDisableContinuousUpdates();
                            break; // skip the code below
                        }
                    }

                    // Enable/disable continuous updates to reflect the GUI setting.
                    final boolean enable = viewer.getOptions()
                                                 .isContinuousUpdates();
                    if ( enable != rfb.continuousUpdatesAreActive() )
                    {
                        if ( enable )
                        {
                            rfb.tryEnableContinuousUpdates( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight() );
                        }
                        else
                        {
                            rfb.tryDisableContinuousUpdates();
                        }
                    }

                    // Finally, request framebuffer update if needed.
                    if ( fullUpdateNeeded )
                    {
                        rfb.writeFramebufferUpdateRequest( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight(),
                                                           false );
                    }
                    else if ( !rfb.continuousUpdatesAreActive() )
                    {
                        rfb.writeFramebufferUpdateRequest( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight(),
                                                           true );
                    }

                    break;

                case RfbProto.SetColourMapEntries:
                    throw new Exception( "Can't handle SetColourMapEntries message" );

                case RfbProto.Bell:
                    Toolkit.getDefaultToolkit()
                           .beep();
                    break;

                case RfbProto.ServerCutText:
                    final String s = rfb.readServerCutText();
                    viewer.getClipboard()
                          .setCutText( s );
                    break;

                case RfbProto.EndOfContinuousUpdates:
                    if ( rfb.continuousUpdatesAreActive() )
                    {
                        rfb.endOfContinuousUpdates();

                        // Change pixel format if such change was pending. Note that we
                        // could not change pixel format while continuous updates were
                        // in effect.
                        boolean incremental = true;
                        if ( viewer.getOptions()
                                   .isEightBitColors() != ( bytesPixel == 1 ) )
                        {
                            setPixelFormat();
                            incremental = false;
                        }
                        // From this point, we ask for updates explicitly.
                        rfb.writeFramebufferUpdateRequest( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight(),
                                                           incremental );
                    }
                    break;

                default:
                    throw new Exception( "Unknown RFB message type " + msgType );
            }
        }
    }

    //
    // Handle a raw rectangle. The second form with paint==false is used
    // by the Hextile decoder for raw-encoded tiles.
    //

    void handleRawRect( final int x, final int y, final int w, final int h )
        throws IOException, Exception
    {
        handleRawRect( x, y, w, h, true );
    }

    void handleRawRect( final int x, final int y, final int w, final int h, final boolean paint )
        throws IOException, Exception
    {
        rawDecoder.handleRect( x, y, w, h );
        if ( paint )
        {
            scheduleRepaint( x, y, w, h );
        }
    }

    //
    // Handle a CopyRect rectangle.
    //

    void handleCopyRect( final int x, final int y, final int w, final int h )
        throws IOException
    {
        copyRectDecoder.handleRect( x, y, w, h );
        scheduleRepaint( x, y, w, h );
    }

    //
    // Handle an RRE-encoded rectangle.
    //

    void handleRRERect( final int x, final int y, final int w, final int h )
        throws IOException
    {
        rreDecoder.handleRect( x, y, w, h );
        scheduleRepaint( x, y, w, h );
    }

    //
    // Handle a CoRRE-encoded rectangle.
    //

    void handleCoRRERect( final int x, final int y, final int w, final int h )
        throws IOException
    {
        correDecoder.handleRect( x, y, w, h );
        scheduleRepaint( x, y, w, h );
    }

    //
    // Handle a Hextile-encoded rectangle.
    //

    void handleHextileRect( final int x, final int y, final int w, final int h )
        throws IOException, Exception
    {
        hextileDecoder.handleRect( x, y, w, h );
    }

    //
    // Handle a ZRLE-encoded rectangle.
    //
    // FIXME: Currently, session recording is not fully supported for ZRLE.
    //

    void handleZRLERect( final int x, final int y, final int w, final int h )
        throws Exception
    {
        zrleDecoder.handleRect( x, y, w, h );
        scheduleRepaint( x, y, w, h );
    }

    //
    // Handle a Zlib-encoded rectangle.
    //

    void handleZlibRect( final int x, final int y, final int w, final int h )
        throws Exception
    {
        zlibDecoder.handleRect( x, y, w, h );
        scheduleRepaint( x, y, w, h );
    }

    //
    // Handle a Tight-encoded rectangle.
    //

    void handleTightRect( final int x, final int y, final int w, final int h )
        throws Exception
    {
        tightDecoder.handleRect( x, y, w, h );
        scheduleRepaint( x, y, w, h );
    }

    //
    // Tell JVM to repaint specified desktop area.
    //

    @Override
    public void scheduleRepaint( final int x, final int y, final int w, final int h )
    {
        // Request repaint, deferred if necessary.
        if ( rfb.getFramebufferWidth() == scaledWidth )
        {
            repaint( viewer.getDeferScreenUpdates(), x, y, w, h );
        }
        else
        {
            final int sx = x * scalingFactor / 100;
            final int sy = y * scalingFactor / 100;
            final int sw = ( ( x + w ) * scalingFactor + 49 ) / 100 - sx + 1;
            final int sh = ( ( y + h ) * scalingFactor + 49 ) / 100 - sy + 1;
            repaint( viewer.getDeferScreenUpdates(), sx, sy, sw, sh );
        }
    }

    //
    // Handle events.
    //

    @Override
    public void keyPressed( final KeyEvent evt )
    {
        processLocalKeyEvent( evt );
    }

    @Override
    public void keyReleased( final KeyEvent evt )
    {
        processLocalKeyEvent( evt );
    }

    @Override
    public void keyTyped( final KeyEvent evt )
    {
        evt.consume();
    }

    @Override
    public void mousePressed( final MouseEvent evt )
    {
        processLocalMouseEvent( evt, false );
    }

    @Override
    public void mouseReleased( final MouseEvent evt )
    {
        processLocalMouseEvent( evt, false );
    }

    @Override
    public void mouseMoved( final MouseEvent evt )
    {
        processLocalMouseEvent( evt, true );
    }

    @Override
    public void mouseDragged( final MouseEvent evt )
    {
        processLocalMouseEvent( evt, true );
    }

    private synchronized void trySendPointerEvent()
    {
        if ( ( needToSendMouseEvent ) && ( mouseEvent != null ) )
        {
            sendMouseEvent( mouseEvent );
            needToSendMouseEvent = false;
            lastMouseEventSendTime = System.currentTimeMillis();
        }
    }

    @Override
    public void run()
    {
        while ( true )
        {
            // Send mouse movement if we have it
            trySendPointerEvent();
            // Sleep for some time
            try
            {
                Thread.sleep( 1000 / mouseMaxFreq );
            }
            catch ( final InterruptedException ex )
            {
            }
        }
    }

    //
    // Ignored events.
    //

    @Override
    public void mouseClicked( final MouseEvent evt )
    {
    }

    @Override
    public void mouseEntered( final MouseEvent evt )
    {
    }

    @Override
    public void mouseExited( final MouseEvent evt )
    {
    }

    //
    // Actual event processing.
    //

    private void processLocalKeyEvent( final KeyEvent evt )
    {
        if ( viewer.getProtocol() != null && rfb.isInNormalProtocol() )
        {
            if ( !inputEnabled )
            {
                if ( ( evt.getKeyChar() == 'r' || evt.getKeyChar() == 'R' ) && evt.getID() == KeyEvent.KEY_PRESSED )
                {
                    // Request screen update.
                    try
                    {
                        rfb.writeFramebufferUpdateRequest( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight(),
                                                           false );
                    }
                    catch ( final IOException e )
                    {
                        e.printStackTrace();
                    }
                }
            }
            else
            {
                // Input enabled.
                synchronized ( rfb )
                {
                    try
                    {
                        rfb.writeKeyEvent( evt );
                    }
                    catch ( final Exception e )
                    {
                        e.printStackTrace();
                    }
                    rfb.notify();
                }
            }
        }
        // Don't ever pass keyboard events to AWT for default processing.
        // Otherwise, pressing Tab would switch focus to ButtonPanel etc.
        evt.consume();
    }

    private void processLocalMouseEvent( final MouseEvent evt, final boolean moved )
    {
        if ( viewer.getProtocol() != null && rfb.isInNormalProtocol() )
        {
            if ( !inSelectionMode )
            {
                if ( inputEnabled )
                {
                    // If mouse not moved, but it's click event then
                    // send it to server immideanlty.
                    // Else, it's mouse movement - we can send it in
                    // our thread later.
                    if ( !moved )
                    {
                        sendMouseEvent( evt );
                    }
                    else
                    {
                        softCursorMove( evt.getX(), evt.getY() );
                        mouseEvent = evt;
                        needToSendMouseEvent = true;
                    }
                }
            }
            else
            {
                handleSelectionMouseEvent( evt );
            }
        }
    }

    private void sendMouseEvent( final MouseEvent evt )
    {
        if ( rfb.getFramebufferWidth() != scaledWidth )
        {
            final int sx = ( evt.getX() * 100 + scalingFactor / 2 ) / scalingFactor;
            final int sy = ( evt.getY() * 100 + scalingFactor / 2 ) / scalingFactor;
            evt.translatePoint( sx - evt.getX(), sy - evt.getY() );
        }
        synchronized ( rfb )
        {
            try
            {
                rfb.writePointerEvent( evt );
            }
            catch ( final Exception e )
            {
                e.printStackTrace();
            }
            rfb.notify();
            lastMouseEventSendTime = System.currentTimeMillis();
        }
    }

    //
    // Reset update statistics.
    //

    void resetStats()
    {
        setStatStartTime( System.currentTimeMillis() );
        setStatNumUpdates( 0 );
        setStatNumTotalRects( 0 );
        setStatNumPixelRects( 0 );
        setStatNumRectsTight( 0 );
        setStatNumRectsTightJPEG( 0 );
        setStatNumRectsZRLE( 0 );
        setStatNumRectsHextile( 0 );
        setStatNumRectsRaw( 0 );
        setStatNumRectsCopy( 0 );
        setStatNumBytesEncoded( 0 );
        setStatNumBytesDecoded( 0 );
        if ( tightDecoder != null )
        {
            tightDecoder.setNumJPEGRects( 0 );
            tightDecoder.setNumTightRects( 0 );
        }
    }

    // ////////////////////////////////////////////////////////////////
    //
    // Handle cursor shape updates (XCursor and RichCursor encodings).
    //

    boolean showSoftCursor = false;

    MemoryImageSource softCursorSource;

    Image softCursor;

    MouseEvent mouseEvent = null;

    boolean needToSendMouseEvent = false;

    int cursorX = 0, cursorY = 0;

    int cursorWidth, cursorHeight;

    int origCursorWidth, origCursorHeight;

    int hotX, hotY;

    int origHotX, origHotY;

    //
    // Handle cursor shape update (XCursor and RichCursor encodings).
    //

    synchronized void handleCursorShapeUpdate( final int encodingType, final int xhot, final int yhot, final int width,
                                               final int height )
        throws IOException
    {

        softCursorFree();

        if ( width * height == 0 )
        {
            return;
        }

        // Ignore cursor shape data if requested by user.
        if ( viewer.getOptions()
                   .isIgnoreCursorUpdates() )
        {
            final int bytesPerRow = ( width + 7 ) / 8;
            final int bytesMaskData = bytesPerRow * height;

            if ( encodingType == RfbProto.EncodingXCursor )
            {
                rfb.skipBytes( 6 + bytesMaskData * 2 );
            }
            else
            {
                // rfb.EncodingRichCursor
                rfb.skipBytes( width * height * bytesPixel + bytesMaskData );
            }
            return;
        }

        // Decode cursor pixel data.
        softCursorSource = decodeCursorShape( encodingType, width, height );

        // Set original (non-scaled) cursor dimensions.
        origCursorWidth = width;
        origCursorHeight = height;
        origHotX = xhot;
        origHotY = yhot;

        // Create off-screen cursor image.
        createSoftCursor();

        // Show the cursor.
        showSoftCursor = true;
        repaint( viewer.getDeferCursorUpdates(), cursorX - hotX, cursorY - hotY, cursorWidth, cursorHeight );
    }

    //
    // decodeCursorShape(). Decode cursor pixel data and return
    // corresponding MemoryImageSource instance.
    //

    synchronized MemoryImageSource decodeCursorShape( final int encodingType, final int width, final int height )
        throws IOException
    {

        final int bytesPerRow = ( width + 7 ) / 8;
        final int bytesMaskData = bytesPerRow * height;

        final int[] softCursorPixels = new int[width * height];

        if ( encodingType == RfbProto.EncodingXCursor )
        {

            // Read foreground and background colors of the cursor.
            final byte[] rgb = new byte[6];
            rfb.readFully( rgb );
            final int[] colors =
                { ( 0xFF000000 | ( rgb[3] & 0xFF ) << 16 | ( rgb[4] & 0xFF ) << 8 | ( rgb[5] & 0xFF ) ),
                    ( 0xFF000000 | ( rgb[0] & 0xFF ) << 16 | ( rgb[1] & 0xFF ) << 8 | ( rgb[2] & 0xFF ) ) };

            // Read pixel and mask data.
            final byte[] pixBuf = new byte[bytesMaskData];
            rfb.readFully( pixBuf );
            final byte[] maskBuf = new byte[bytesMaskData];
            rfb.readFully( maskBuf );

            // Decode pixel data into softCursorPixels[].
            byte pixByte, maskByte;
            int x, y, n, result;
            int i = 0;
            for ( y = 0; y < height; y++ )
            {
                for ( x = 0; x < width / 8; x++ )
                {
                    pixByte = pixBuf[y * bytesPerRow + x];
                    maskByte = maskBuf[y * bytesPerRow + x];
                    for ( n = 7; n >= 0; n-- )
                    {
                        if ( ( maskByte >> n & 1 ) != 0 )
                        {
                            result = colors[pixByte >> n & 1];
                        }
                        else
                        {
                            result = 0; // Transparent pixel
                        }
                        softCursorPixels[i++] = result;
                    }
                }
                for ( n = 7; n >= 8 - width % 8; n-- )
                {
                    if ( ( maskBuf[y * bytesPerRow + x] >> n & 1 ) != 0 )
                    {
                        result = colors[pixBuf[y * bytesPerRow + x] >> n & 1];
                    }
                    else
                    {
                        result = 0; // Transparent pixel
                    }
                    softCursorPixels[i++] = result;
                }
            }

        }
        else
        {
            // encodingType == rfb.EncodingRichCursor

            // Read pixel and mask data.
            final byte[] pixBuf = new byte[width * height * bytesPixel];
            rfb.readFully( pixBuf );
            final byte[] maskBuf = new byte[bytesMaskData];
            rfb.readFully( maskBuf );

            // Decode pixel data into softCursorPixels[].
            byte maskByte;
            int x, y, n, result;
            int i = 0;
            for ( y = 0; y < height; y++ )
            {
                for ( x = 0; x < width / 8; x++ )
                {
                    maskByte = maskBuf[y * bytesPerRow + x];
                    for ( n = 7; n >= 0; n-- )
                    {
                        if ( ( maskByte >> n & 1 ) != 0 )
                        {
                            if ( bytesPixel == 1 )
                            {
                                result = cm8.getRGB( pixBuf[i] );
                            }
                            else
                            {
                                result =
                                    0xFF000000 | ( pixBuf[i * 4 + 2] & 0xFF ) << 16 | ( pixBuf[i * 4 + 1] & 0xFF ) << 8
                                        | ( pixBuf[i * 4] & 0xFF );
                            }
                        }
                        else
                        {
                            result = 0; // Transparent pixel
                        }
                        softCursorPixels[i++] = result;
                    }
                }
                for ( n = 7; n >= 8 - width % 8; n-- )
                {
                    if ( ( maskBuf[y * bytesPerRow + x] >> n & 1 ) != 0 )
                    {
                        if ( bytesPixel == 1 )
                        {
                            result = cm8.getRGB( pixBuf[i] );
                        }
                        else
                        {
                            result =
                                0xFF000000 | ( pixBuf[i * 4 + 2] & 0xFF ) << 16 | ( pixBuf[i * 4 + 1] & 0xFF ) << 8
                                    | ( pixBuf[i * 4] & 0xFF );
                        }
                    }
                    else
                    {
                        result = 0; // Transparent pixel
                    }
                    softCursorPixels[i++] = result;
                }
            }

        }

        return new MemoryImageSource( width, height, softCursorPixels, 0, width );
    }

    //
    // createSoftCursor(). Assign softCursor new Image (scaled if necessary).
    // Uses softCursorSource as a source for new cursor image.
    //

    synchronized void createSoftCursor()
    {

        if ( softCursorSource == null )
        {
            return;
        }

        int scaleCursor = viewer.getOptions()
                                .getScaleCursor();
        if ( scaleCursor == 0 || !inputEnabled )
        {
            scaleCursor = 100;
        }

        // Save original cursor coordinates.
        int x = cursorX - hotX;
        int y = cursorY - hotY;
        int w = cursorWidth;
        int h = cursorHeight;

        cursorWidth = ( origCursorWidth * scaleCursor + 50 ) / 100;
        cursorHeight = ( origCursorHeight * scaleCursor + 50 ) / 100;
        hotX = ( origHotX * scaleCursor + 50 ) / 100;
        hotY = ( origHotY * scaleCursor + 50 ) / 100;
        softCursor = Toolkit.getDefaultToolkit()
                            .createImage( softCursorSource );

        if ( scaleCursor != 100 )
        {
            softCursor = softCursor.getScaledInstance( cursorWidth, cursorHeight, Image.SCALE_SMOOTH );
        }

        if ( showSoftCursor )
        {
            // Compute screen area to update.
            x = Math.min( x, cursorX - hotX );
            y = Math.min( y, cursorY - hotY );
            w = Math.max( w, cursorWidth );
            h = Math.max( h, cursorHeight );

            repaint( viewer.getDeferCursorUpdates(), x, y, w, h );
        }
    }

    //
    // softCursorMove(). Moves soft cursor into a particular location.
    //

    synchronized void softCursorMove( final int x, final int y )
    {
        final int oldX = cursorX;
        final int oldY = cursorY;
        cursorX = x;
        cursorY = y;
        if ( showSoftCursor )
        {
            repaint( viewer.getDeferCursorUpdates(), oldX - hotX, oldY - hotY, cursorWidth, cursorHeight );
            repaint( viewer.getDeferCursorUpdates(), cursorX - hotX, cursorY - hotY, cursorWidth, cursorHeight );
        }
    }

    //
    // softCursorFree(). Remove soft cursor, dispose resources.
    //

    public synchronized void softCursorFree()
    {
        if ( showSoftCursor )
        {
            showSoftCursor = false;
            softCursor = null;
            softCursorSource = null;

            repaint( viewer.getDeferCursorUpdates(), cursorX - hotX, cursorY - hotY, cursorWidth, cursorHeight );
        }
    }

    // ////////////////////////////////////////////////////////////////
    //
    // Support for selecting a rectangular video area.
    //

    /** This flag is false in normal operation, and true in the selection mode. */
    private boolean inSelectionMode;

    /** The point where the selection was started. */
    private Point selectionStart;

    /** The second point of the selection. */
    private Point selectionEnd;

    /**
     * We change cursor when enabling the selection mode. In this variable, we save the original cursor so we can
     * restore it on returning to the normal mode.
     */
    private Cursor savedCursor;

    /**
     * Initialize selection-related varibles.
     */
    private synchronized void resetSelection()
    {
        inSelectionMode = false;
        selectionStart = new Point( 0, 0 );
        selectionEnd = new Point( 0, 0 );

        savedCursor = getCursor();
    }

    /**
     * Check current state of the selection mode.
     * 
     * @return true in the selection mode, false otherwise.
     */
    public boolean isInSelectionMode()
    {
        return inSelectionMode;
    }

    /**
     * Get current selection.
     * 
     * @param useScreenCoords use screen coordinates if true, or framebuffer coordinates if false. This makes difference
     *            when scaling factor is not 100.
     * @return The selection as a {@link Rectangle}.
     */
    private synchronized Rectangle getSelection( final boolean useScreenCoords )
    {
        int x0 = selectionStart.x;
        int x1 = selectionEnd.x;
        int y0 = selectionStart.y;
        int y1 = selectionEnd.y;
        // Make x and y point to the upper left corner of the selection.
        if ( x1 < x0 )
        {
            final int t = x0;
            x0 = x1;
            x1 = t;
        }
        if ( y1 < y0 )
        {
            final int t = y0;
            y0 = y1;
            y1 = t;
        }
        // Include the borders in the selection (unless it's empty).
        if ( x0 != x1 && y0 != y1 )
        {
            x1 += 1;
            y1 += 1;
        }
        // Translate from screen coordinates to framebuffer coordinates.
        if ( rfb.getFramebufferWidth() != scaledWidth )
        {
            x0 = ( x0 * 100 + scalingFactor / 2 ) / scalingFactor;
            y0 = ( y0 * 100 + scalingFactor / 2 ) / scalingFactor;
            x1 = ( x1 * 100 + scalingFactor / 2 ) / scalingFactor;
            y1 = ( y1 * 100 + scalingFactor / 2 ) / scalingFactor;
        }
        // Clip the selection to framebuffer.
        if ( x0 < 0 )
        {
            x0 = 0;
        }
        if ( y0 < 0 )
        {
            y0 = 0;
        }
        if ( x1 > rfb.getFramebufferWidth() )
        {
            x1 = rfb.getFramebufferWidth();
        }
        if ( y1 > rfb.getFramebufferHeight() )
        {
            y1 = rfb.getFramebufferHeight();
        }
        // Make width a multiple of 16.
        final int widthBlocks = ( x1 - x0 + 8 ) / 16;
        if ( selectionStart.x <= selectionEnd.x )
        {
            x1 = x0 + widthBlocks * 16;
            if ( x1 > rfb.getFramebufferWidth() )
            {
                x1 -= 16;
            }
        }
        else
        {
            x0 = x1 - widthBlocks * 16;
            if ( x0 < 0 )
            {
                x0 += 16;
            }
        }
        // Make height a multiple of 8.
        final int heightBlocks = ( y1 - y0 + 4 ) / 8;
        if ( selectionStart.y <= selectionEnd.y )
        {
            y1 = y0 + heightBlocks * 8;
            if ( y1 > rfb.getFramebufferHeight() )
            {
                y1 -= 8;
            }
        }
        else
        {
            y0 = y1 - heightBlocks * 8;
            if ( y0 < 0 )
            {
                y0 += 8;
            }
        }
        // Translate the selection back to screen coordinates if requested.
        if ( useScreenCoords && rfb.getFramebufferWidth() != scaledWidth )
        {
            x0 = ( x0 * scalingFactor + 50 ) / 100;
            y0 = ( y0 * scalingFactor + 50 ) / 100;
            x1 = ( x1 * scalingFactor + 50 ) / 100;
            y1 = ( y1 * scalingFactor + 50 ) / 100;
        }
        // Construct and return the result.
        return new Rectangle( x0, y0, x1 - x0, y1 - y0 );
    }

    /**
     * Enable or disable the selection mode.
     * 
     * @param enable enables the selection mode if true, disables if fasle.
     */
    public synchronized void enableSelection( final boolean enable )
    {
        if ( enable && !inSelectionMode )
        {
            // Enter the selection mode.
            inSelectionMode = true;
            savedCursor = getCursor();
            setCursor( Cursor.getPredefinedCursor( Cursor.CROSSHAIR_CURSOR ) );
            repaint();
        }
        else if ( !enable && inSelectionMode )
        {
            // Leave the selection mode.
            inSelectionMode = false;
            setCursor( savedCursor );
            repaint();
        }
    }

    /**
     * Process mouse events in the selection mode.
     * 
     * @param evt mouse event that was originally passed to {@link MouseListener} or {@link MouseMotionListener}.
     */
    private synchronized void handleSelectionMouseEvent( final MouseEvent evt )
    {
        final int id = evt.getID();
        final boolean button1 = ( evt.getModifiers() & InputEvent.BUTTON1_MASK ) != 0;

        if ( id == MouseEvent.MOUSE_PRESSED && button1 )
        {
            selectionStart = selectionEnd = evt.getPoint();
            repaint();
        }
        if ( id == MouseEvent.MOUSE_DRAGGED && button1 )
        {
            selectionEnd = evt.getPoint();
            repaint();
        }
        if ( id == MouseEvent.MOUSE_RELEASED && button1 )
        {
            try
            {
                rfb.trySendVideoSelection( getSelection( false ) );
            }
            catch ( final IOException e )
            {
                e.printStackTrace();
            }
        }
    }

    public boolean isFirstSizeAutoUpdate()
    {
        return isFirstSizeAutoUpdate;
    }

    public void setFirstSizeAutoUpdate( final boolean isFirstSizeAutoUpdate )
    {
        this.isFirstSizeAutoUpdate = isFirstSizeAutoUpdate;
    }

    public long getStatStartTime()
    {
        return statStartTime;
    }

    public void setStatStartTime( final long statStartTime )
    {
        this.statStartTime = statStartTime;
    }

    public long getStatNumUpdates()
    {
        return statNumUpdates;
    }

    public void setStatNumUpdates( final long statNumUpdates )
    {
        this.statNumUpdates = statNumUpdates;
    }

    public long getStatNumPixelRects()
    {
        return statNumPixelRects;
    }

    public void setStatNumPixelRects( final long statNumPixelRects )
    {
        this.statNumPixelRects = statNumPixelRects;
    }

    public long getStatNumTotalRects()
    {
        return statNumTotalRects;
    }

    public void setStatNumTotalRects( final long statNumTotalRects )
    {
        this.statNumTotalRects = statNumTotalRects;
    }

    public long getStatNumRectsZRLE()
    {
        return statNumRectsZRLE;
    }

    public void setStatNumRectsZRLE( final long statNumRectsZRLE )
    {
        this.statNumRectsZRLE = statNumRectsZRLE;
    }

    public long getStatNumRectsTight()
    {
        return statNumRectsTight;
    }

    public void setStatNumRectsTight( final long statNumRectsTight )
    {
        this.statNumRectsTight = statNumRectsTight;
    }

    public long getStatNumRectsHextile()
    {
        return statNumRectsHextile;
    }

    public void setStatNumRectsHextile( final long statNumRectsHextile )
    {
        this.statNumRectsHextile = statNumRectsHextile;
    }

    public long getStatNumRectsRaw()
    {
        return statNumRectsRaw;
    }

    public void setStatNumRectsRaw( final long statNumRectsRaw )
    {
        this.statNumRectsRaw = statNumRectsRaw;
    }

    public long getStatNumRectsCopy()
    {
        return statNumRectsCopy;
    }

    public void setStatNumRectsCopy( final long statNumRectsCopy )
    {
        this.statNumRectsCopy = statNumRectsCopy;
    }

    public long getStatNumRectsTightJPEG()
    {
        return statNumRectsTightJPEG;
    }

    public void setStatNumRectsTightJPEG( final long statNumRectsTightJPEG )
    {
        this.statNumRectsTightJPEG = statNumRectsTightJPEG;
    }

    public long getStatNumBytesDecoded()
    {
        return statNumBytesDecoded;
    }

    public void setStatNumBytesDecoded( final long statNumBytesDecoded )
    {
        this.statNumBytesDecoded = statNumBytesDecoded;
    }

    public long getStatNumBytesEncoded()
    {
        return statNumBytesEncoded;
    }

    public void setStatNumBytesEncoded( final long statNumBytesEncoded )
    {
        this.statNumBytesEncoded = statNumBytesEncoded;
    }
}
