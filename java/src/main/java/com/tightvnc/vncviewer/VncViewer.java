//
//  Copyright (C) 2001-2004 HorizonLive.com, Inc.  All Rights Reserved.
//  Copyright (C) 2002 Constantin Kaplinsky.  All Rights Reserved.
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

//
// VncViewer.java - the VNC viewer applet.  This class mainly just sets up the
// user interface, leaving it to the VncCanvas to do the actual rendering of
// a VNC desktop.
//

package com.tightvnc.vncviewer;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.ScrollPane;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

import com.tightvnc.vncviewer.proto.DesCipher;
import com.tightvnc.vncviewer.proto.RfbProto;
import com.tightvnc.vncviewer.ui.AuthPanel;
import com.tightvnc.vncviewer.ui.ButtonPanel;
import com.tightvnc.vncviewer.ui.ClipboardFrame;
import com.tightvnc.vncviewer.ui.OptionsFrame;
import com.tightvnc.vncviewer.ui.RecordingFrame;
import com.tightvnc.vncviewer.ui.ReloginPanel;
import com.tightvnc.vncviewer.ui.VncCanvas;

public class VncViewer
    extends java.applet.Applet
    implements java.lang.Runnable, WindowListener, ComponentListener
{

    //
    // main() is called when run as a java program from the command line.
    // It simply runs the applet inside a newly-created frame.
    //

    public static void main( final String[] argv )
    {
        final VncViewer v = new VncViewer();
        v.mainArgs = argv;
        v.inAnApplet = false;
        v.inSeparateFrame = true;

        v.init();
        v.start();
    }

    private boolean inAnApplet = true;

    private boolean inSeparateFrame = false;

    private String[] mainArgs;

    private RfbProto rfb;

    private Thread rfbThread;

    private Frame vncFrame;

    private Container vncContainer;

    private ScrollPane desktopScrollPane;

    private GridBagLayout gridbag;

    private ButtonPanel buttonPanel;

    private Label connStatusLabel;

    private VncCanvas vc;

    private OptionsFrame options;

    private ClipboardFrame clipboard;

    private RecordingFrame rec;

    // Control session recording.
    private Object recordingSync;

    private String sessionFileName;

    private boolean recordingActive;

    private boolean recordingStatusChanged;

    private String cursorUpdatesDef;

    private String eightBitColorsDef;

    // Variables read from parameter values.
    private String socketFactory;

    private String host;

    private int port;

    private String passwordParam;

    private boolean showControls;

    private boolean offerRelogin;

    private boolean showOfflineDesktop;

    private int deferScreenUpdates;

    private int deferCursorUpdates;

    private int deferUpdateRequests;

    private int debugStatsExcludeUpdates;

    private int debugStatsMeasureUpdates;

    private int[] encodingsSaved;

    private int nEncodingsSaved;

    // Reference to this applet for inter-applet communication.
    public static java.applet.Applet refApplet;

    //
    // init()
    //

    @Override
    public void init()
    {

        readParameters();

        refApplet = this;

        if ( inSeparateFrame )
        {
            vncFrame = new Frame( "TightVNC" );
            if ( !inAnApplet )
            {
                vncFrame.add( "Center", this );
            }
            vncContainer = vncFrame;
        }
        else
        {
            vncContainer = this;
        }

        recordingSync = new Object();

        options = new OptionsFrame( this );
        clipboard = new ClipboardFrame( this );
        if ( RecordingFrame.checkSecurity() )
        {
            rec = new RecordingFrame( this );
        }

        sessionFileName = null;
        recordingActive = false;
        recordingStatusChanged = false;
        cursorUpdatesDef = null;
        eightBitColorsDef = null;

        if ( inSeparateFrame )
        {
            vncFrame.addWindowListener( this );
            vncFrame.addComponentListener( this );
        }

        rfbThread = new Thread( this );
        rfbThread.start();
    }

    @Override
    public void update( final Graphics g )
    {
    }

    //
    // run() - executed by the rfbThread to deal with the RFB socket.
    //

    @Override
    public void run()
    {

        gridbag = new GridBagLayout();
        vncContainer.setLayout( gridbag );

        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        if ( showControls )
        {
            buttonPanel = new ButtonPanel( this );
            gridbag.setConstraints( buttonPanel, gbc );
            vncContainer.add( buttonPanel );
        }

        try
        {
            connectAndAuthenticate();
            doProtocolInitialisation();

            if ( showControls && rfb.getClientMsgCaps()
                                    .isEnabled( RfbProto.EnableVideoHandling ) )
            {
                buttonPanel.addVideoOffButton();
            }

            if ( showControls && rfb.getClientMsgCaps()
                                    .isEnabled( RfbProto.VideoRectangleSelection ) )
            {
                buttonPanel.addSelectButton();
            }

            if ( showControls && rfb.getClientMsgCaps()
                                    .isEnabled( RfbProto.VideoFreeze ) )
            {
                buttonPanel.addVideoFreezeButton();
            }

            // FIXME: Use auto-scaling not only in a separate frame.
            if ( options.isAutoScale() && inSeparateFrame )
            {
                Dimension screenSize;
                try
                {
                    screenSize = vncContainer.getToolkit()
                                             .getScreenSize();
                }
                catch ( final Exception e )
                {
                    screenSize = new Dimension( 0, 0 );
                }
                createCanvas( screenSize.width - 32, screenSize.height - 32 );
            }
            else
            {
                createCanvas( 0, 0 );
            }

            gbc.weightx = 1.0;
            gbc.weighty = 1.0;

            if ( inSeparateFrame )
            {

                // Create a panel which itself is resizeable and can hold
                // non-resizeable VncCanvas component at the top left corner.
                final Panel canvasPanel = new Panel();
                canvasPanel.setLayout( new FlowLayout( FlowLayout.LEFT, 0, 0 ) );
                canvasPanel.add( vc );

                // Create a ScrollPane which will hold a panel with VncCanvas
                // inside.
                desktopScrollPane = new ScrollPane( ScrollPane.SCROLLBARS_AS_NEEDED );
                gbc.fill = GridBagConstraints.BOTH;
                gridbag.setConstraints( desktopScrollPane, gbc );
                desktopScrollPane.add( canvasPanel );
                desktopScrollPane.setWheelScrollingEnabled( false );
                // If auto scale is not enabled we don't need to set first frame
                // size to fullscreen
                if ( !options.isAutoScale() )
                {
                    vc.setFirstSizeAutoUpdate( false );
                }

                // Finally, add our ScrollPane to the Frame window.
                vncFrame.add( desktopScrollPane );
                vncFrame.setTitle( rfb.getDesktopName() );
                vncFrame.pack();
                vc.resizeDesktopFrame();
            }
            else
            {
                // Just add the VncCanvas component to the Applet.
                gridbag.setConstraints( vc, gbc );
                add( vc );
                validate();
            }

            if ( showControls )
            {
                buttonPanel.enableButtons();
            }

            moveFocusToDesktop();
            processNormalProtocol();

        }
        catch ( final NoRouteToHostException e )
        {
            fatalError( "Network error: no route to server: " + host, e );
        }
        catch ( final UnknownHostException e )
        {
            fatalError( "Network error: server name unknown: " + host, e );
        }
        catch ( final ConnectException e )
        {
            fatalError( "Network error: could not connect to server: " + host + ":" + port, e );
        }
        catch ( final EOFException e )
        {
            if ( showOfflineDesktop )
            {
                e.printStackTrace();
                System.out.println( "Network error: remote side closed connection" );
                if ( vc != null )
                {
                    vc.enableInput( false );
                }
                if ( inSeparateFrame )
                {
                    vncFrame.setTitle( rfb.getDesktopName() + " [disconnected]" );
                }
                if ( rfb != null && !rfb.closed() )
                {
                    rfb.close();
                }
                if ( showControls && buttonPanel != null )
                {
                    buttonPanel.disableButtonsOnDisconnect();
                    if ( inSeparateFrame )
                    {
                        vncFrame.pack();
                    }
                    else
                    {
                        validate();
                    }
                }
            }
            else
            {
                fatalError( "Network error: remote side closed connection", e );
            }
        }
        catch ( final IOException e )
        {
            final String str = e.getMessage();
            if ( str != null && str.length() != 0 )
            {
                fatalError( "Network Error: " + str, e );
            }
            else
            {
                fatalError( e.toString(), e );
            }
        }
        catch ( final Exception e )
        {
            final String str = e.getMessage();
            if ( str != null && str.length() != 0 )
            {
                fatalError( "Error: " + str, e );
            }
            else
            {
                fatalError( e.toString(), e );
            }
        }

    }

    //
    // Create a VncCanvas instance.
    //

    void createCanvas( final int maxWidth, final int maxHeight )
        throws IOException
    {
        // Determine if Java 2D API is available and use a special
        // version of VncCanvas if it is present.
        vc = null;
        try
        {
            // This throws ClassNotFoundException if there is no Java 2D API and
            // no Mouse Wheel support.
            Class cl = Class.forName( "java.awt.Graphics2D" );
            cl = Class.forName( "java.awt.event.MouseWheelEvent" );

            // Create VncCanvas2
            cl = Class.forName( "com.tightvnc.vncviewer.VncCanvas2" );
            final Class[] argClasses = { this.getClass(), Integer.TYPE, Integer.TYPE };
            final java.lang.reflect.Constructor cstr = cl.getConstructor( argClasses );
            final Object[] argObjects = { this, new Integer( maxWidth ), new Integer( maxHeight ) };
            vc = (VncCanvas) cstr.newInstance( argObjects );
        }
        catch ( final Exception e )
        {
            System.out.println( "Warning: Java 2D API is not available" );
        }

        // If we failed to create VncCanvas2D, use old VncCanvas.
        if ( vc == null )
        {
            vc = new VncCanvas( this, maxWidth, maxHeight );
        }
    }

    //
    // Process RFB socket messages.
    // If the rfbThread is being stopped, ignore any exceptions,
    // otherwise rethrow the exception so it can be handled.
    //

    void processNormalProtocol()
        throws Exception
    {
        try
        {
            vc.processNormalProtocol();
        }
        catch ( final Exception e )
        {
            if ( rfbThread == null )
            {
                System.out.println( "Ignoring RFB socket exceptions" + " because applet is stopping" );
            }
            else
            {
                throw e;
            }
        }
    }

    //
    // Connect to the RFB server and authenticate the user.
    //

    void connectAndAuthenticate()
        throws Exception
    {
        showConnectionStatus( "Initializing..." );
        if ( inSeparateFrame )
        {
            vncFrame.pack();
            vncFrame.show();
        }
        else
        {
            validate();
        }

        showConnectionStatus( "Connecting to " + host + ", port " + port + "..." );

        rfb = new RfbProto( host, port, this );
        showConnectionStatus( "Connected to server" );

        rfb.readVersionMsg();
        showConnectionStatus( "RFB server supports protocol version " + rfb.getServerMajor() + "."
            + rfb.getServerMinor() );

        rfb.writeVersionMsg();
        showConnectionStatus( "Using RFB protocol version " + rfb.getClientMajor() + "." + rfb.getClientMinor() );

        final int secType = rfb.negotiateSecurity();
        int authType;
        if ( secType == RfbProto.SecTypeTight )
        {
            showConnectionStatus( "Enabling TightVNC protocol extensions" );
            rfb.setupTunneling();
            authType = rfb.negotiateAuthenticationTight();
        }
        else
        {
            authType = secType;
        }

        switch ( authType )
        {
            case RfbProto.AuthNone:
                showConnectionStatus( "No authentication needed" );
                rfb.authenticateNone();
                break;
            case RfbProto.AuthVNC:
                showConnectionStatus( "Performing standard VNC authentication" );
                if ( passwordParam != null )
                {
                    rfb.authenticateVNC( passwordParam );
                }
                else
                {
                    final String pw = askPassword();
                    rfb.authenticateVNC( pw );
                }
                break;
            default:
                throw new Exception( "Unknown authentication scheme " + authType );
        }
    }

    //
    // Show a message describing the connection status.
    // To hide the connection status label, use (msg == null).
    //

    void showConnectionStatus( final String msg )
    {
        if ( msg == null )
        {
            if ( vncContainer.isAncestorOf( connStatusLabel ) )
            {
                vncContainer.remove( connStatusLabel );
            }
            return;
        }

        System.out.println( msg );

        if ( connStatusLabel == null )
        {
            connStatusLabel = new Label( "Status: " + msg );
            connStatusLabel.setFont( new Font( "Helvetica", Font.PLAIN, 12 ) );
        }
        else
        {
            connStatusLabel.setText( "Status: " + msg );
        }

        if ( !vncContainer.isAncestorOf( connStatusLabel ) )
        {
            final GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.weightx = 1.0;
            gbc.weighty = 1.0;
            gbc.insets = new Insets( 20, 30, 20, 30 );
            gridbag.setConstraints( connStatusLabel, gbc );
            vncContainer.add( connStatusLabel );
        }

        if ( inSeparateFrame )
        {
            vncFrame.pack();
        }
        else
        {
            validate();
        }
    }

    //
    // Show an authentication panel.
    //

    String askPassword()
        throws Exception
    {
        showConnectionStatus( null );

        final AuthPanel authPanel = new AuthPanel( this );

        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.ipadx = 100;
        gbc.ipady = 50;
        gridbag.setConstraints( authPanel, gbc );
        vncContainer.add( authPanel );

        if ( inSeparateFrame )
        {
            vncFrame.pack();
        }
        else
        {
            validate();
        }

        authPanel.moveFocusToDefaultField();
        final String pw = authPanel.getPassword();
        vncContainer.remove( authPanel );

        return pw;
    }

    //
    // Do the rest of the protocol initialisation.
    //

    void doProtocolInitialisation()
        throws IOException
    {
        rfb.writeClientInit();
        rfb.readServerInit();

        System.out.println( "Desktop name is " + rfb.getDesktopName() );
        System.out.println( "Desktop size is " + rfb.getFramebufferWidth() + " x " + rfb.getFramebufferHeight() );

        setEncodings();

        showConnectionStatus( null );
    }

    //
    // Send current encoding list to the RFB server.
    //
    public void setEncodings()
    {
        setEncodings( false );
    }

    public void autoSelectEncodings()
    {
        setEncodings( true );
    }

    void setEncodings( final boolean autoSelectOnly )
    {
        if ( options == null || rfb == null || !rfb.isInNormalProtocol() )
        {
            return;
        }

        int preferredEncoding = options.getPreferredEncoding();
        if ( preferredEncoding == -1 )
        {
            final long kbitsPerSecond = rfb.kbitsPerSecond();
            if ( nEncodingsSaved < 1 )
            {
                // Choose Tight or ZRLE encoding for the very first update.
                System.out.println( "Using Tight/ZRLE encodings" );
                preferredEncoding = RfbProto.EncodingTight;
            }
            else if ( kbitsPerSecond > 2000 && encodingsSaved[0] != RfbProto.EncodingHextile )
            {
                // Switch to Hextile if the connection speed is above 2Mbps.
                System.out.println( "Throughput " + kbitsPerSecond + " kbit/s - changing to Hextile encoding" );
                preferredEncoding = RfbProto.EncodingHextile;
            }
            else if ( kbitsPerSecond < 1000 && encodingsSaved[0] != RfbProto.EncodingTight )
            {
                // Switch to Tight/ZRLE if the connection speed is below 1Mbps.
                System.out.println( "Throughput " + kbitsPerSecond + " kbit/s - changing to Tight/ZRLE encodings" );
                preferredEncoding = RfbProto.EncodingTight;
            }
            else
            {
                // Don't change the encoder.
                if ( autoSelectOnly )
                {
                    return;
                }
                preferredEncoding = encodingsSaved[0];
            }
        }
        else
        {
            // Auto encoder selection is not enabled.
            if ( autoSelectOnly )
            {
                return;
            }
        }

        final int[] encodings = new int[20];
        int nEncodings = 0;

        encodings[nEncodings++] = preferredEncoding;
        if ( options.isUseCopyRect() )
        {
            encodings[nEncodings++] = RfbProto.EncodingCopyRect;
        }

        if ( preferredEncoding != RfbProto.EncodingTight )
        {
            encodings[nEncodings++] = RfbProto.EncodingTight;
        }
        if ( preferredEncoding != RfbProto.EncodingZRLE )
        {
            encodings[nEncodings++] = RfbProto.EncodingZRLE;
        }
        if ( preferredEncoding != RfbProto.EncodingHextile )
        {
            encodings[nEncodings++] = RfbProto.EncodingHextile;
        }
        if ( preferredEncoding != RfbProto.EncodingZlib )
        {
            encodings[nEncodings++] = RfbProto.EncodingZlib;
        }
        if ( preferredEncoding != RfbProto.EncodingCoRRE )
        {
            encodings[nEncodings++] = RfbProto.EncodingCoRRE;
        }
        if ( preferredEncoding != RfbProto.EncodingRRE )
        {
            encodings[nEncodings++] = RfbProto.EncodingRRE;
        }

        if ( options.getCompressLevel() >= 0 && options.getCompressLevel() <= 9 )
        {
            encodings[nEncodings++] = RfbProto.EncodingCompressLevel0 + options.getCompressLevel();
        }
        if ( options.getJpegQuality() >= 0 && options.getJpegQuality() <= 9 )
        {
            encodings[nEncodings++] = RfbProto.EncodingQualityLevel0 + options.getJpegQuality();
        }

        if ( options.isRequestCursorUpdates() )
        {
            encodings[nEncodings++] = RfbProto.EncodingXCursor;
            encodings[nEncodings++] = RfbProto.EncodingRichCursor;
            if ( !options.isIgnoreCursorUpdates() )
            {
                encodings[nEncodings++] = RfbProto.EncodingPointerPos;
            }
        }

        encodings[nEncodings++] = RfbProto.EncodingLastRect;
        encodings[nEncodings++] = RfbProto.EncodingNewFBSize;

        boolean encodingsWereChanged = false;
        if ( nEncodings != nEncodingsSaved )
        {
            encodingsWereChanged = true;
        }
        else
        {
            for ( int i = 0; i < nEncodings; i++ )
            {
                if ( encodings[i] != encodingsSaved[i] )
                {
                    encodingsWereChanged = true;
                    break;
                }
            }
        }

        if ( encodingsWereChanged )
        {
            try
            {
                rfb.writeSetEncodings( encodings, nEncodings );
                if ( vc != null )
                {
                    vc.softCursorFree();
                }
            }
            catch ( final Exception e )
            {
                e.printStackTrace();
            }
            encodingsSaved = encodings;
            nEncodingsSaved = nEncodings;
        }
    }

    //
    // setCutText() - send the given cut text to the RFB server.
    //

    public void setCutText( final String text )
    {
        try
        {
            if ( rfb != null && rfb.isInNormalProtocol() )
            {
                rfb.writeClientCutText( text );
            }
        }
        catch ( final Exception e )
        {
            e.printStackTrace();
        }
    }

    //
    // Order change in session recording status. To stop recording, pass
    // null in place of the fname argument.
    //

    public void setRecordingStatus( final String fname )
    {
        synchronized ( recordingSync )
        {
            sessionFileName = fname;
            recordingStatusChanged = true;
        }
    }

    //
    // Start or stop session recording. Returns true if this method call
    // causes recording of a new session.
    //

    public boolean checkRecordingStatus()
        throws IOException
    {
        synchronized ( recordingSync )
        {
            if ( recordingStatusChanged )
            {
                recordingStatusChanged = false;
                if ( sessionFileName != null )
                {
                    startRecording();
                    return true;
                }
                else
                {
                    stopRecording();
                }
            }
        }
        return false;
    }

    //
    // Start session recording.
    //

    protected void startRecording()
        throws IOException
    {
        synchronized ( recordingSync )
        {
            if ( !recordingActive )
            {
                // Save settings to restore them after recording the session.
                cursorUpdatesDef = options.getChoices()[options.getCursorUpdatesIndex()].getSelectedItem();
                eightBitColorsDef = options.getChoices()[options.getEightBitColorsIndex()].getSelectedItem();
                // Set options to values suitable for recording.
                options.getChoices()[options.getCursorUpdatesIndex()].select( "Disable" );
                options.getChoices()[options.getCursorUpdatesIndex()].setEnabled( false );
                options.setEncodings();
                options.getChoices()[options.getEightBitColorsIndex()].select( "No" );
                options.getChoices()[options.getEightBitColorsIndex()].setEnabled( false );
                options.setColorFormat();
            }
            else
            {
                rfb.closeSession();
            }

            System.out.println( "Recording the session in " + sessionFileName );
            rfb.startSession( sessionFileName );
            recordingActive = true;
        }
    }

    //
    // Stop session recording.
    //

    protected void stopRecording()
        throws IOException
    {
        synchronized ( recordingSync )
        {
            if ( recordingActive )
            {
                // Restore options.
                options.getChoices()[options.getCursorUpdatesIndex()].select( cursorUpdatesDef );
                options.getChoices()[options.getCursorUpdatesIndex()].setEnabled( true );
                options.setEncodings();
                options.getChoices()[options.getEightBitColorsIndex()].select( eightBitColorsDef );
                options.getChoices()[options.getEightBitColorsIndex()].setEnabled( true );
                options.setColorFormat();

                rfb.closeSession();
                System.out.println( "Session recording stopped." );
            }
            sessionFileName = null;
            recordingActive = false;
        }
    }

    //
    // readParameters() - read parameters from the html source or from the
    // command line. On the command line, the arguments are just a sequence of
    // param_name/param_value pairs where the names and values correspond to
    // those expected in the html applet tag source.
    //

    void readParameters()
    {
        host = readParameter( "HOST", !inAnApplet );
        if ( host == null )
        {
            host = getCodeBase().getHost();
            if ( host.equals( "" ) )
            {
                fatalError( "HOST parameter not specified" );
            }
        }

        port = readIntParameter( "PORT", 5900 );

        // Read "ENCPASSWORD" or "PASSWORD" parameter if specified.
        readPasswordParameters();

        String str;
        if ( inAnApplet )
        {
            str = readParameter( "Open New Window", false );
            if ( str != null && str.equalsIgnoreCase( "Yes" ) )
            {
                inSeparateFrame = true;
            }
        }

        // "Show Controls" set to "No" disables button panel.
        showControls = true;
        str = readParameter( "Show Controls", false );
        if ( str != null && str.equalsIgnoreCase( "No" ) )
        {
            showControls = false;
        }

        // "Offer Relogin" set to "No" disables "Login again" and "Close
        // window" buttons under error messages in applet mode.
        offerRelogin = true;
        str = readParameter( "Offer Relogin", false );
        if ( str != null && str.equalsIgnoreCase( "No" ) )
        {
            offerRelogin = false;
        }

        // Do we continue showing desktop on remote disconnect?
        showOfflineDesktop = false;
        str = readParameter( "Show Offline Desktop", false );
        if ( str != null && str.equalsIgnoreCase( "Yes" ) )
        {
            showOfflineDesktop = true;
        }

        // Fine tuning options.
        deferScreenUpdates = readIntParameter( "Defer screen updates", 20 );
        deferCursorUpdates = readIntParameter( "Defer cursor updates", 10 );
        deferUpdateRequests = readIntParameter( "Defer update requests", 0 );

        // Debugging options.
        debugStatsExcludeUpdates = readIntParameter( "DEBUG_XU", 0 );
        debugStatsMeasureUpdates = readIntParameter( "DEBUG_CU", 0 );

        // SocketFactory.
        socketFactory = readParameter( "SocketFactory", false );
    }

    //
    // Read password parameters. If an "ENCPASSWORD" parameter is set,
    // then decrypt the password into the passwordParam string. Otherwise,
    // try to read the "PASSWORD" parameter directly to passwordParam.
    //

    private void readPasswordParameters()
    {
        final String encPasswordParam = readParameter( "ENCPASSWORD", false );
        if ( encPasswordParam == null )
        {
            passwordParam = readParameter( "PASSWORD", false );
        }
        else
        {
            // ENCPASSWORD is hexascii-encoded. Decode.
            final byte[] pw = { 0, 0, 0, 0, 0, 0, 0, 0 };
            int len = encPasswordParam.length() / 2;
            if ( len > 8 )
            {
                len = 8;
            }
            for ( int i = 0; i < len; i++ )
            {
                final String hex = encPasswordParam.substring( i * 2, i * 2 + 2 );
                final Integer x = new Integer( Integer.parseInt( hex, 16 ) );
                pw[i] = x.byteValue();
            }
            // Decrypt the password.
            final byte[] key = { 23, 82, 107, 6, 35, 78, 88, 7 };
            final DesCipher des = new DesCipher( key );
            des.decrypt( pw, 0, pw, 0 );
            passwordParam = new String( pw );
        }
    }

    public String readParameter( final String name, final boolean required )
    {
        if ( inAnApplet )
        {
            final String s = getParameter( name );
            if ( ( s == null ) && required )
            {
                fatalError( name + " parameter not specified" );
            }
            return s;
        }

        for ( int i = 0; i < mainArgs.length; i += 2 )
        {
            if ( mainArgs[i].equalsIgnoreCase( name ) )
            {
                try
                {
                    return mainArgs[i + 1];
                }
                catch ( final Exception e )
                {
                    if ( required )
                    {
                        fatalError( name + " parameter not specified" );
                    }
                    return null;
                }
            }
        }
        if ( required )
        {
            fatalError( name + " parameter not specified" );
        }
        return null;
    }

    int readIntParameter( final String name, final int defaultValue )
    {
        final String str = readParameter( name, false );
        int result = defaultValue;
        if ( str != null )
        {
            try
            {
                result = Integer.parseInt( str );
            }
            catch ( final NumberFormatException e )
            {
            }
        }
        return result;
    }

    //
    // moveFocusToDesktop() - move keyboard focus either to VncCanvas.
    //

    public void moveFocusToDesktop()
    {
        if ( vncContainer != null )
        {
            if ( vc != null && vncContainer.isAncestorOf( vc ) )
            {
                vc.requestFocus();
            }
        }
    }

    //
    // disconnect() - close connection to server.
    //

    synchronized public void disconnect()
    {
        System.out.println( "Disconnecting" );

        if ( vc != null )
        {
            final double sec = ( System.currentTimeMillis() - vc.getStatStartTime() ) / 1000.0;
            final double rate = Math.round( vc.getStatNumUpdates() / sec * 100 ) / 100.0;
            final long nRealRects = vc.getStatNumPixelRects();
            final long nPseudoRects = vc.getStatNumTotalRects() - vc.getStatNumPixelRects();
            System.out.println( "Updates received: " + vc.getStatNumUpdates() + " (" + nRealRects + " rectangles + "
                + nPseudoRects + " pseudo), " + rate + " updates/sec" );
            final long numRectsOther =
                nRealRects - vc.getStatNumRectsTight() - vc.getStatNumRectsZRLE() - vc.getStatNumRectsHextile()
                    - vc.getStatNumRectsRaw() - vc.getStatNumRectsCopy();
            System.out.println( "Rectangles:" + " Tight=" + vc.getStatNumRectsTight() + "(JPEG="
                + vc.getStatNumRectsTightJPEG() + ") ZRLE=" + vc.getStatNumRectsZRLE() + " Hextile="
                + vc.getStatNumRectsHextile() + " Raw=" + vc.getStatNumRectsRaw() + " CopyRect="
                + vc.getStatNumRectsCopy() + " other=" + numRectsOther );

            final long raw = vc.getStatNumBytesDecoded();
            final long compressed = vc.getStatNumBytesEncoded();
            if ( compressed > 0 )
            {
                final double ratio = Math.round( (double) raw / compressed * 1000 ) / 1000.0;
                System.out.println( "Pixel data: " + vc.getStatNumBytesDecoded() + " bytes, "
                    + vc.getStatNumBytesEncoded() + " compressed, ratio " + ratio );
            }
        }

        if ( rfb != null && !rfb.closed() )
        {
            rfb.close();
        }
        options.dispose();
        clipboard.dispose();
        if ( rec != null )
        {
            rec.dispose();
        }

        if ( inAnApplet )
        {
            showMessage( "Disconnected" );
        }
        else
        {
            System.exit( 0 );
        }
    }

    //
    // fatalError() - print out a fatal error message.
    // FIXME: Do we really need two versions of the fatalError() method?
    //

    synchronized public void fatalError( final String str )
    {
        System.out.println( str );

        if ( inAnApplet )
        {
            // vncContainer null, applet not inited,
            // can not present the error to the user.
            Thread.currentThread()
                  .stop();
        }
        else
        {
            System.exit( 1 );
        }
    }

    synchronized public void fatalError( final String str, final Exception e )
    {

        if ( rfb != null && rfb.closed() )
        {
            // Not necessary to show error message if the error was caused
            // by I/O problems after the rfb.close() method call.
            System.out.println( "RFB thread finished" );
            return;
        }

        System.out.println( str );
        e.printStackTrace();

        if ( rfb != null )
        {
            rfb.close();
        }

        if ( inAnApplet )
        {
            showMessage( str );
        }
        else
        {
            System.exit( 1 );
        }
    }

    //
    // Show message text and optionally "Relogin" and "Close" buttons.
    //

    void showMessage( final String msg )
    {
        vncContainer.removeAll();

        final Label errLabel = new Label( msg, Label.CENTER );
        errLabel.setFont( new Font( "Helvetica", Font.PLAIN, 12 ) );

        if ( offerRelogin )
        {

            final Panel gridPanel = new Panel( new GridLayout( 0, 1 ) );
            final Panel outerPanel = new Panel( new FlowLayout( FlowLayout.LEFT ) );
            outerPanel.add( gridPanel );
            vncContainer.setLayout( new FlowLayout( FlowLayout.LEFT, 30, 16 ) );
            vncContainer.add( outerPanel );
            final Panel textPanel = new Panel( new FlowLayout( FlowLayout.CENTER ) );
            textPanel.add( errLabel );
            gridPanel.add( textPanel );
            gridPanel.add( new ReloginPanel( this ) );

        }
        else
        {

            vncContainer.setLayout( new FlowLayout( FlowLayout.LEFT, 30, 30 ) );
            vncContainer.add( errLabel );

        }

        if ( inSeparateFrame )
        {
            vncFrame.pack();
        }
        else
        {
            validate();
        }
    }

    //
    // Stop the applet.
    // Main applet thread will terminate on first exception
    // after seeing that rfbThread has been set to null.
    //

    @Override
    public void stop()
    {
        System.out.println( "Stopping applet" );
        rfbThread = null;
    }

    //
    // This method is called before the applet is destroyed.
    //

    @Override
    public void destroy()
    {
        System.out.println( "Destroying applet" );

        vncContainer.removeAll();
        options.dispose();
        clipboard.dispose();
        if ( rec != null )
        {
            rec.dispose();
        }
        if ( rfb != null && !rfb.closed() )
        {
            rfb.close();
        }
        if ( inSeparateFrame )
        {
            vncFrame.dispose();
        }
    }

    //
    // Start/stop receiving mouse events.
    //

    public void enableInput( final boolean enable )
    {
        vc.enableInput( enable );
    }

    //
    // Resize framebuffer if autoScale is enabled.
    //

    @Override
    public void componentResized( final ComponentEvent e )
    {
        if ( e.getComponent() == vncFrame )
        {
            if ( options.isAutoScale() )
            {
                if ( vc != null )
                {
                    if ( !vc.isFirstSizeAutoUpdate() )
                    {
                        vc.updateFramebufferSize();
                    }
                }
            }
        }
    }

    //
    // Ignore component events we're not interested in.
    //

    @Override
    public void componentShown( final ComponentEvent e )
    {
    }

    @Override
    public void componentMoved( final ComponentEvent e )
    {
    }

    @Override
    public void componentHidden( final ComponentEvent e )
    {
    }

    //
    // Close application properly on window close event.
    //

    @Override
    public void windowClosing( final WindowEvent evt )
    {
        System.out.println( "Closing window" );
        if ( rfb != null )
        {
            disconnect();
        }

        vncContainer.hide();

        if ( !inAnApplet )
        {
            System.exit( 0 );
        }
    }

    //
    // Ignore window events we're not interested in.
    //

    @Override
    public void windowActivated( final WindowEvent evt )
    {
    }

    @Override
    public void windowDeactivated( final WindowEvent evt )
    {
    }

    @Override
    public void windowOpened( final WindowEvent evt )
    {
    }

    @Override
    public void windowClosed( final WindowEvent evt )
    {
    }

    @Override
    public void windowIconified( final WindowEvent evt )
    {
    }

    @Override
    public void windowDeiconified( final WindowEvent evt )
    {
    }

    public VncCanvas getVncCanvas()
    {
        return vc;
    }

    public Frame getVncFrame()
    {
        return vncFrame;
    }

    public RecordingFrame getRecordingFrame()
    {
        return rec;
    }

    public void toggleOptionsVisibility()
    {
        options.setVisible( !options.isVisible() );
    }

    public void toggleRecordingFrameVisibility()
    {
        rec.setVisible( !rec.isVisible() );
    }

    public void toggleClipboardVisibilitiy()
    {
        clipboard.setVisible( !clipboard.isVisible() );
    }

    public RfbProto getProtocol()
    {
        return rfb;
    }

    public OptionsFrame getOptions()
    {
        return options;
    }

    public boolean isShowControls()
    {
        return showControls;
    }

    public ButtonPanel getButtonPanel()
    {
        return buttonPanel;
    }

    public int getDeferCursorUpdates()
    {
        return deferCursorUpdates;
    }

    public int getDeferScreenUpdates()
    {
        return deferScreenUpdates;
    }

    public boolean isInAnApplet()
    {
        return inAnApplet;
    }

    public boolean isInSeparateFrame()
    {
        return inSeparateFrame;
    }

    public String[] getMainArgs()
    {
        return mainArgs;
    }

    public RfbProto getRfb()
    {
        return rfb;
    }

    public Thread getRfbThread()
    {
        return rfbThread;
    }

    public Container getVncContainer()
    {
        return vncContainer;
    }

    public ScrollPane getDesktopScrollPane()
    {
        return desktopScrollPane;
    }

    public GridBagLayout getGridbag()
    {
        return gridbag;
    }

    public Label getConnStatusLabel()
    {
        return connStatusLabel;
    }

    public VncCanvas getVc()
    {
        return vc;
    }

    public ClipboardFrame getClipboard()
    {
        return clipboard;
    }

    public RecordingFrame getRec()
    {
        return rec;
    }

    public Object getRecordingSync()
    {
        return recordingSync;
    }

    public String getSessionFileName()
    {
        return sessionFileName;
    }

    public boolean isRecordingActive()
    {
        return recordingActive;
    }

    public boolean isRecordingStatusChanged()
    {
        return recordingStatusChanged;
    }

    public String getCursorUpdatesDef()
    {
        return cursorUpdatesDef;
    }

    public String getEightBitColorsDef()
    {
        return eightBitColorsDef;
    }

    public String getSocketFactory()
    {
        return socketFactory;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public String getPasswordParam()
    {
        return passwordParam;
    }

    public boolean isOfferRelogin()
    {
        return offerRelogin;
    }

    public boolean isShowOfflineDesktop()
    {
        return showOfflineDesktop;
    }

    public int getDeferUpdateRequests()
    {
        return deferUpdateRequests;
    }

    public int getDebugStatsExcludeUpdates()
    {
        return debugStatsExcludeUpdates;
    }

    public int getDebugStatsMeasureUpdates()
    {
        return debugStatsMeasureUpdates;
    }

    public int[] getEncodingsSaved()
    {
        return encodingsSaved;
    }

    public int getnEncodingsSaved()
    {
        return nEncodingsSaved;
    }
}
