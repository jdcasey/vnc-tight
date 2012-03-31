//
//  Copyright (C) 2001 HorizonLive.com, Inc.  All Rights Reserved.
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

//
// Options frame.
//
// This deals with all the options the user can play with.
// It sets the encodings array and some booleans.
//

package com.tightvnc.vncviewer.ui;

import java.awt.Button;
import java.awt.Choice;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Label;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;

import com.tightvnc.vncviewer.VncViewer;
import com.tightvnc.vncviewer.proto.RfbProto;

public class OptionsFrame
    extends Frame
    implements WindowListener, ActionListener, ItemListener
{

    private static final String[] names = { "Encoding", "Compression level", "JPEG image quality",
        "Cursor shape updates", "Use CopyRect", "Continuous updates", "Restricted colors", "Mouse buttons 2 and 3",
        "View only", "Scaling factor", "Scale remote cursor", "Share desktop" };

    private static final String[][] values = { { "Auto", "Raw", "RRE", "CoRRE", "Hextile", "Zlib", "Tight", "ZRLE" },
        { "Default", "1", "2", "3", "4", "5", "6", "7", "8", "9" },
        { "JPEG off", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" }, { "Enable", "Ignore", "Disable" },
        { "Yes", "No" }, { "Yes", "No" }, { "Yes", "No" }, { "Normal", "Reversed" }, { "Yes", "No" },
        { "Auto", "1%", "5%", "10%", "20%", "25%", "50%", "75%", "100%" }, { "No", "50%", "75%", "125%", "150%" },
        { "Yes", "No" } };

    private final int encodingIndex = 0, compressLevelIndex = 1, jpegQualityIndex = 2, cursorUpdatesIndex = 3,
                    useCopyRectIndex = 4, contUpdatesIndex = 5, eightBitColorsIndex = 6, mouseButtonIndex = 7,
                    viewOnlyIndex = 8, scalingFactorIndex = 9, scaleCursorIndex = 10, shareDesktopIndex = 11;

    private final Label[] labels = new Label[names.length];

    private final Choice[] choices = new Choice[names.length];

    private final Button closeButton;

    private final VncViewer viewer;

    //
    // The actual data which other classes look at:
    //

    private int preferredEncoding;

    private int compressLevel;

    private int jpegQuality;

    private boolean useCopyRect;

    private boolean continuousUpdates;

    private boolean requestCursorUpdates;

    private boolean ignoreCursorUpdates;

    private boolean eightBitColors;

    private boolean reverseMouseButtons2And3;

    private boolean shareDesktop;

    private boolean viewOnly;

    private int scaleCursor;

    private boolean autoScale;

    private int scalingFactor;

    //
    // Constructor. Set up the labels and choices from the names and values
    // arrays.
    //

    public OptionsFrame( final VncViewer v )
    {
        super( "TightVNC Options" );

        viewer = v;

        final GridBagLayout gridbag = new GridBagLayout();
        setLayout( gridbag );

        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;

        for ( int i = 0; i < names.length; i++ )
        {
            labels[i] = new Label( names[i] );
            gbc.gridwidth = 1;
            gridbag.setConstraints( labels[i], gbc );
            add( labels[i] );

            choices[i] = new Choice();
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gridbag.setConstraints( choices[i], gbc );
            add( choices[i] );
            choices[i].addItemListener( this );

            for ( int j = 0; j < values[i].length; j++ )
            {
                choices[i].addItem( values[i][j] );
            }
        }

        closeButton = new Button( "Close" );
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gridbag.setConstraints( closeButton, gbc );
        add( closeButton );
        closeButton.addActionListener( this );

        pack();

        addWindowListener( this );

        // Set up defaults

        choices[encodingIndex].select( "Auto" );
        choices[compressLevelIndex].select( "Default" );
        choices[jpegQualityIndex].select( "6" );
        choices[cursorUpdatesIndex].select( "Enable" );
        choices[useCopyRectIndex].select( "Yes" );
        choices[contUpdatesIndex].select( "No" );
        choices[eightBitColorsIndex].select( "No" );
        choices[mouseButtonIndex].select( "Normal" );
        choices[viewOnlyIndex].select( "No" );
        choices[scaleCursorIndex].select( "No" );
        choices[shareDesktopIndex].select( "Yes" );

        // But let them be overridden by parameters

        for ( int i = 0; i < names.length; i++ )
        {
            final String s = viewer.readParameter( names[i], false );
            if ( s != null )
            {
                for ( int j = 0; j < values[i].length; j++ )
                {
                    if ( s.equalsIgnoreCase( values[i][j] ) )
                    {
                        choices[i].select( j );
                    }
                }
            }
        }

        // Get scaling factor from parameters and set it
        // to gui and class member scalingFactor

        String s = viewer.readParameter( "Scaling Factor", false );
        if ( s == null )
        {
            s = "100%";
        }
        setScalingFactor( s );
        if ( autoScale )
        {
            choices[scalingFactorIndex].select( "Auto" );
        }
        else
        {
            choices[scalingFactorIndex].select( s );
        }

        // Make the booleans and encodings array correspond to the state of the GUI

        setEncodings();
        setColorFormat();
        setContinuousUpdates();
        setOtherOptions();
    }

    //
    // Set scaling factor class member value
    //

    void setScalingFactor( final int sf )
    {
        setScalingFactor( new Integer( sf ).toString() );
    }

    void setScalingFactor( String s )
    {
        autoScale = false;
        scalingFactor = 100;
        if ( s != null )
        {
            if ( s.equalsIgnoreCase( "Auto" ) )
            {
                autoScale = true;
            }
            else
            {
                // Remove the '%' char at the end of string if present.
                if ( s.charAt( s.length() - 1 ) == '%' )
                {
                    s = s.substring( 0, s.length() - 1 );
                }
                // Convert to an integer.
                try
                {
                    scalingFactor = Integer.parseInt( s );
                }
                catch ( final NumberFormatException e )
                {
                    scalingFactor = 100;
                }
                // Make sure scalingFactor is in the range of [1..1000].
                if ( scalingFactor < 1 )
                {
                    scalingFactor = 1;
                }
                else if ( scalingFactor > 1000 )
                {
                    scalingFactor = 1000;
                }
            }
        }
    }

    //
    // Disable the shareDesktop option
    //

    public void disableShareDesktop()
    {
        labels[shareDesktopIndex].setEnabled( false );
        choices[shareDesktopIndex].setEnabled( false );
    }

    //
    // Disable the "Continuous updates" option. This method is called
    // when we figure out that the server does not support corresponding
    // protocol extensions.
    //

    public void disableContUpdates()
    {
        labels[contUpdatesIndex].setEnabled( false );
        choices[contUpdatesIndex].setEnabled( false );
        choices[contUpdatesIndex].select( "No" );
        continuousUpdates = false;
    }

    //
    // setEncodings looks at the encoding, compression level, JPEG
    // quality level, cursor shape updates and copyRect choices and sets
    // corresponding variables properly. Then it calls the VncViewer's
    // setEncodings method to send a SetEncodings message to the RFB
    // server.
    //

    public void setEncodings()
    {
        useCopyRect = choices[useCopyRectIndex].getSelectedItem()
                                               .equals( "Yes" );

        preferredEncoding = RfbProto.EncodingRaw;
        boolean enableCompressLevel = false;
        boolean enableQualityLevel = false;

        if ( choices[encodingIndex].getSelectedItem()
                                   .equals( "RRE" ) )
        {
            preferredEncoding = RfbProto.EncodingRRE;
        }
        else if ( choices[encodingIndex].getSelectedItem()
                                        .equals( "CoRRE" ) )
        {
            preferredEncoding = RfbProto.EncodingCoRRE;
        }
        else if ( choices[encodingIndex].getSelectedItem()
                                        .equals( "Hextile" ) )
        {
            preferredEncoding = RfbProto.EncodingHextile;
        }
        else if ( choices[encodingIndex].getSelectedItem()
                                        .equals( "ZRLE" ) )
        {
            preferredEncoding = RfbProto.EncodingZRLE;
        }
        else if ( choices[encodingIndex].getSelectedItem()
                                        .equals( "Zlib" ) )
        {
            preferredEncoding = RfbProto.EncodingZlib;
            enableCompressLevel = true;
        }
        else if ( choices[encodingIndex].getSelectedItem()
                                        .equals( "Tight" ) )
        {
            preferredEncoding = RfbProto.EncodingTight;
            enableCompressLevel = true;
            enableQualityLevel = !eightBitColors;
        }
        else if ( choices[encodingIndex].getSelectedItem()
                                        .equals( "Auto" ) )
        {
            preferredEncoding = -1;
            enableQualityLevel = !eightBitColors;
        }

        // Handle compression level setting.

        try
        {
            compressLevel = Integer.parseInt( choices[compressLevelIndex].getSelectedItem() );
        }
        catch ( final NumberFormatException e )
        {
            compressLevel = -1;
        }
        if ( compressLevel < 1 || compressLevel > 9 )
        {
            compressLevel = -1;
        }
        labels[compressLevelIndex].setEnabled( enableCompressLevel );
        choices[compressLevelIndex].setEnabled( enableCompressLevel );

        // Handle JPEG quality setting.

        try
        {
            jpegQuality = Integer.parseInt( choices[jpegQualityIndex].getSelectedItem() );
        }
        catch ( final NumberFormatException e )
        {
            jpegQuality = -1;
        }
        if ( jpegQuality < 0 || jpegQuality > 9 )
        {
            jpegQuality = -1;
        }
        labels[jpegQualityIndex].setEnabled( enableQualityLevel );
        choices[jpegQualityIndex].setEnabled( enableQualityLevel );

        // Request cursor shape updates if necessary.

        requestCursorUpdates = !choices[cursorUpdatesIndex].getSelectedItem()
                                                           .equals( "Disable" );

        if ( requestCursorUpdates )
        {
            ignoreCursorUpdates = choices[cursorUpdatesIndex].getSelectedItem()
                                                             .equals( "Ignore" );
        }

        viewer.setEncodings();
    }

    //
    // setColorFormat sets eightBitColors variable depending on the GUI
    // setting, causing switches between 8-bit and 24-bit colors mode if
    // necessary.
    //

    public void setColorFormat()
    {

        eightBitColors = choices[eightBitColorsIndex].getSelectedItem()
                                                     .equals( "Yes" );

        final boolean enableJPEG =
            !eightBitColors && ( choices[encodingIndex].getSelectedItem()
                                                       .equals( "Tight" ) || choices[encodingIndex].getSelectedItem()
                                                                                                   .equals( "Auto" ) );

        labels[jpegQualityIndex].setEnabled( enableJPEG );
        choices[jpegQualityIndex].setEnabled( enableJPEG );
    }

    //
    // setContinuousUpdates sets continuousUpdates variable depending on
    // the GUI setting. VncViewer monitors the state of this variable and
    // send corresponding protocol messages to the server when necessary.
    //

    void setContinuousUpdates()
    {

        continuousUpdates = choices[contUpdatesIndex].getSelectedItem()
                                                     .equals( "Yes" );
    }

    //
    // setOtherOptions looks at the "other" choices (ones that do not
    // cause sending any protocol messages) and sets the boolean flags
    // appropriately.
    //

    void setOtherOptions()
    {

        reverseMouseButtons2And3 = choices[mouseButtonIndex].getSelectedItem()
                                                            .equals( "Reversed" );

        viewOnly = choices[viewOnlyIndex].getSelectedItem()
                                         .equals( "Yes" );
        if ( viewer.getVncCanvas() != null )
        {
            viewer.getVncCanvas()
                  .enableInput( !viewOnly );
        }

        shareDesktop = choices[shareDesktopIndex].getSelectedItem()
                                                 .equals( "Yes" );

        String scaleString = choices[scaleCursorIndex].getSelectedItem();
        if ( scaleString.endsWith( "%" ) )
        {
            scaleString = scaleString.substring( 0, scaleString.length() - 1 );
        }
        try
        {
            scaleCursor = Integer.parseInt( scaleString );
        }
        catch ( final NumberFormatException e )
        {
            scaleCursor = 0;
        }
        if ( scaleCursor < 10 || scaleCursor > 500 )
        {
            scaleCursor = 0;
        }
        if ( requestCursorUpdates && !ignoreCursorUpdates && !viewOnly )
        {
            labels[scaleCursorIndex].setEnabled( true );
            choices[scaleCursorIndex].setEnabled( true );
        }
        else
        {
            labels[scaleCursorIndex].setEnabled( false );
            choices[scaleCursorIndex].setEnabled( false );
        }
        if ( viewer.getVncCanvas() != null )
        {
            viewer.getVncCanvas()
                  .createSoftCursor(); // update cursor scaling
        }
    }

    //
    // Respond to actions on Choice controls
    //

    @Override
    public void itemStateChanged( final ItemEvent evt )
    {
        final Object source = evt.getSource();

        if ( source == choices[encodingIndex] || source == choices[compressLevelIndex]
            || source == choices[jpegQualityIndex] || source == choices[cursorUpdatesIndex]
            || source == choices[useCopyRectIndex] )
        {

            setEncodings();

            if ( source == choices[cursorUpdatesIndex] )
            {
                setOtherOptions(); // update scaleCursor state
            }

        }
        else if ( source == choices[eightBitColorsIndex] )
        {

            setColorFormat();

        }
        else if ( source == choices[contUpdatesIndex] )
        {

            setContinuousUpdates();

        }
        else if ( source == choices[mouseButtonIndex] || source == choices[shareDesktopIndex]
            || source == choices[viewOnlyIndex] || source == choices[scaleCursorIndex] )
        {

            setOtherOptions();

        }
        else if ( source == choices[scalingFactorIndex] )
        {
            // Tell VNC canvas that scaling factor has changed
            setScalingFactor( choices[scalingFactorIndex].getSelectedItem() );
            if ( viewer.getVncCanvas() != null )
            {
                viewer.getVncCanvas()
                      .setScalingFactor( scalingFactor );
            }
        }
    }

    //
    // Respond to button press
    //

    @Override
    public void actionPerformed( final ActionEvent evt )
    {
        if ( evt.getSource() == closeButton )
        {
            setVisible( false );
        }
    }

    //
    // Respond to window events
    //

    @Override
    public void windowClosing( final WindowEvent evt )
    {
        setVisible( false );
    }

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

    public static String[] getNames()
    {
        return names;
    }

    public static String[][] getValues()
    {
        return values;
    }

    public int getEncodingIndex()
    {
        return encodingIndex;
    }

    public int getCompressLevelIndex()
    {
        return compressLevelIndex;
    }

    public int getJpegQualityIndex()
    {
        return jpegQualityIndex;
    }

    public int getCursorUpdatesIndex()
    {
        return cursorUpdatesIndex;
    }

    public int getUseCopyRectIndex()
    {
        return useCopyRectIndex;
    }

    public int getContUpdatesIndex()
    {
        return contUpdatesIndex;
    }

    public int getEightBitColorsIndex()
    {
        return eightBitColorsIndex;
    }

    public int getMouseButtonIndex()
    {
        return mouseButtonIndex;
    }

    public int getViewOnlyIndex()
    {
        return viewOnlyIndex;
    }

    public int getScalingFactorIndex()
    {
        return scalingFactorIndex;
    }

    public int getScaleCursorIndex()
    {
        return scaleCursorIndex;
    }

    public int getShareDesktopIndex()
    {
        return shareDesktopIndex;
    }

    public Label[] getLabels()
    {
        return labels;
    }

    public Choice[] getChoices()
    {
        return choices;
    }

    public Button getCloseButton()
    {
        return closeButton;
    }

    public VncViewer getViewer()
    {
        return viewer;
    }

    public int getPreferredEncoding()
    {
        return preferredEncoding;
    }

    public int getCompressLevel()
    {
        return compressLevel;
    }

    public int getJpegQuality()
    {
        return jpegQuality;
    }

    public boolean isUseCopyRect()
    {
        return useCopyRect;
    }

    public boolean isContinuousUpdates()
    {
        return continuousUpdates;
    }

    public boolean isRequestCursorUpdates()
    {
        return requestCursorUpdates;
    }

    public boolean isIgnoreCursorUpdates()
    {
        return ignoreCursorUpdates;
    }

    public boolean isEightBitColors()
    {
        return eightBitColors;
    }

    public boolean isReverseMouseButtons2And3()
    {
        return reverseMouseButtons2And3;
    }

    public boolean isShareDesktop()
    {
        return shareDesktop;
    }

    public boolean isViewOnly()
    {
        return viewOnly;
    }

    public int getScaleCursor()
    {
        return scaleCursor;
    }

    public boolean isAutoScale()
    {
        return autoScale;
    }

    public int getScalingFactor()
    {
        return scalingFactor;
    }

    public void setContinuousUpdates( final boolean continuousUpdates )
    {
        this.continuousUpdates = continuousUpdates;
    }

}
