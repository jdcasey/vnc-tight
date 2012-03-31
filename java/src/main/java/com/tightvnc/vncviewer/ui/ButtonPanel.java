//
//  Copyright (C) 2001,2002 HorizonLive.com, Inc.  All Rights Reserved.
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
// ButtonPanel class implements panel with four buttons in the
// VNCViewer desktop window.
//

package com.tightvnc.vncviewer.ui;

import java.awt.Button;
import java.awt.FlowLayout;
import java.awt.Panel;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;

import com.tightvnc.vncviewer.VncViewer;
import com.tightvnc.vncviewer.proto.RfbProto;

public class ButtonPanel
    extends Panel
    implements ActionListener
{

    VncViewer viewer;

    Button disconnectButton;

    Button optionsButton;

    Button recordButton;

    Button clipboardButton;

    Button ctrlAltDelButton;

    Button refreshButton;

    Button enableVideoButton;

    Button selectButton;

    Button videoFreezeButton;

    final String videoOffLabel = "Video Off";

    final String videoOnLabel = "Video On";

    final String selectEnterLabel = "Select Video Area";

    final String selectLeaveLabel = "Hide Selection";

    final String videoFreezeLabel = " Freeze Video  ";

    final String videoUnfreezeLabel = "Unfreeze Video";

    public ButtonPanel( final VncViewer v )
    {
        viewer = v;

        setLayout( new FlowLayout( FlowLayout.LEFT, 0, 0 ) );
        disconnectButton = new Button( "Disconnect" );
        disconnectButton.setEnabled( false );
        add( disconnectButton );
        disconnectButton.addActionListener( this );
        optionsButton = new Button( "Options" );
        add( optionsButton );
        optionsButton.addActionListener( this );
        clipboardButton = new Button( "Clipboard" );
        clipboardButton.setEnabled( false );
        add( clipboardButton );
        clipboardButton.addActionListener( this );
        if ( viewer.getRecordingFrame() != null )
        {
            recordButton = new Button( "Record" );
            add( recordButton );
            recordButton.addActionListener( this );
        }
        ctrlAltDelButton = new Button( "Send Ctrl-Alt-Del" );
        ctrlAltDelButton.setEnabled( false );
        add( ctrlAltDelButton );
        ctrlAltDelButton.addActionListener( this );
        refreshButton = new Button( "Refresh" );
        refreshButton.setEnabled( false );
        add( refreshButton );
        refreshButton.addActionListener( this );
    }

    /**
     * Add video on/off button to the ButtonPanel.
     */
    public void addVideoOffButton()
    {
        enableVideoButton = new Button( videoOffLabel );
        enableVideoButton.setEnabled( false );
        add( enableVideoButton );
        enableVideoButton.addActionListener( this );
    }

    /**
     * Add video selection button to the ButtonPanel.
     */
    public void addSelectButton()
    {
        selectButton = new Button( selectEnterLabel );
        selectButton.setEnabled( false );
        add( selectButton );
        selectButton.addActionListener( this );
    }

    /**
     * Add video ignore button to the ButtonPanel.
     */
    public void addVideoFreezeButton()
    {
        videoFreezeButton = new Button( videoFreezeLabel );
        videoFreezeButton.setEnabled( false );
        add( videoFreezeButton );
        videoFreezeButton.addActionListener( this );
    }

    //
    // Enable buttons on successful connection.
    //

    public void enableButtons()
    {
        disconnectButton.setEnabled( true );
        clipboardButton.setEnabled( true );
        refreshButton.setEnabled( true );
        if ( enableVideoButton != null )
        {
            enableVideoButton.setEnabled( true );
        }
        if ( selectButton != null )
        {
            selectButton.setEnabled( true );
        }
        if ( videoFreezeButton != null )
        {
            videoFreezeButton.setEnabled( true );
        }
    }

    //
    // Disable all buttons on disconnect.
    //

    public void disableButtonsOnDisconnect()
    {
        remove( disconnectButton );
        disconnectButton = new Button( "Hide desktop" );
        disconnectButton.setEnabled( true );
        add( disconnectButton, 0 );
        disconnectButton.addActionListener( this );

        optionsButton.setEnabled( false );
        clipboardButton.setEnabled( false );
        ctrlAltDelButton.setEnabled( false );
        refreshButton.setEnabled( false );
        if ( enableVideoButton != null )
        {
            enableVideoButton.setEnabled( false );
        }
        if ( selectButton != null )
        {
            selectButton.setEnabled( false );
        }
        if ( videoFreezeButton != null )
        {
            videoFreezeButton.setEnabled( false );
        }
    }

    //
    // Enable/disable controls that should not be available in view-only
    // mode.
    //

    public void enableRemoteAccessControls( final boolean enable )
    {
        ctrlAltDelButton.setEnabled( enable );
    }

    //
    // Event processing.
    //

    @Override
    public void actionPerformed( final ActionEvent evt )
    {

        viewer.moveFocusToDesktop();

        if ( evt.getSource() == disconnectButton )
        {
            viewer.disconnect();

        }
        else if ( evt.getSource() == optionsButton )
        {
            viewer.toggleOptionsVisibility();

        }
        else if ( evt.getSource() == recordButton )
        {
            viewer.toggleRecordingFrameVisibility();

        }
        else if ( evt.getSource() == clipboardButton )
        {
            viewer.toggleClipboardVisibilitiy();

        }
        else if ( evt.getSource() == ctrlAltDelButton )
        {
            try
            {
                final int modifiers = InputEvent.CTRL_MASK | InputEvent.ALT_MASK;

                KeyEvent ctrlAltDelEvent = new KeyEvent( this, KeyEvent.KEY_PRESSED, 0, modifiers, 127 );
                viewer.getProtocol()
                      .writeKeyEvent( ctrlAltDelEvent );

                ctrlAltDelEvent = new KeyEvent( this, KeyEvent.KEY_RELEASED, 0, modifiers, 127 );
                viewer.getProtocol()
                      .writeKeyEvent( ctrlAltDelEvent );

            }
            catch ( final IOException e )
            {
                e.printStackTrace();
            }

        }
        else if ( evt.getSource() == refreshButton )
        {
            try
            {
                final RfbProto rfb = viewer.getProtocol();
                rfb.writeFramebufferUpdateRequest( 0, 0, rfb.getFramebufferWidth(), rfb.getFramebufferHeight(), false );
            }
            catch ( final IOException e )
            {
                e.printStackTrace();
            }

        }
        else if ( enableVideoButton != null && evt.getSource() == enableVideoButton )
        {
            final boolean enable = enableVideoButton.getLabel()
                                                    .equals( videoOnLabel );
            try
            {
                viewer.getProtocol()
                      .trySendVideoEnable( enable );
                enableVideoButton.setLabel( enable ? videoOffLabel : videoOnLabel );
            }
            catch ( final IOException e )
            {
                e.printStackTrace();
            }

        }
        else if ( selectButton != null && evt.getSource() == selectButton )
        {
            if ( viewer.getVncCanvas() != null )
            {
                final boolean isSelecting = viewer.getVncCanvas()
                                                  .isInSelectionMode();
                if ( !isSelecting )
                {
                    selectButton.setLabel( selectLeaveLabel );
                    viewer.getVncCanvas()
                          .enableSelection( true );
                }
                else
                {
                    selectButton.setLabel( selectEnterLabel );
                    viewer.getVncCanvas()
                          .enableSelection( false );
                }
            }

        }
        else if ( videoFreezeButton != null && evt.getSource() == videoFreezeButton )
        {
            final boolean freeze = videoFreezeButton.getLabel()
                                                    .equals( videoFreezeLabel );
            try
            {
                viewer.getProtocol()
                      .trySendVideoFreeze( freeze );
                videoFreezeButton.setLabel( freeze ? videoUnfreezeLabel : videoFreezeLabel );
            }
            catch ( final IOException ex )
            {
                ex.printStackTrace();
            }

        }
    }
}
