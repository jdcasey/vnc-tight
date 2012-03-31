package com.tightvnc.vncviewer.io;

import java.io.DataOutput;
import java.io.IOException;

import com.tightvnc.vncviewer.proto.RfbProto;

public class RecordOutputStream
    implements DataOutput
{

    public RecordOutputStream( final RfbProto rfbproto )
    {
        rfb = rfbproto;
    }

    private boolean canWrite()
    {
        return ( ( rfb != null ) && ( rfb.getSessionRecorder() != null ) );
    }

    @Override
    public void write( final byte[] b )
        throws IOException
    {
        if ( canWrite() )
        {
            rfb.getSessionRecorder()
               .write( b );
        }
    }

    @Override
    public void write( final byte[] b, final int off, final int len )
        throws IOException
    {
        if ( canWrite() )
        {
            rfb.getSessionRecorder()
               .write( b, off, len );
        }
    }

    @Override
    public void write( final int b )
        throws IOException
    {
        if ( canWrite() )
        {
            rfb.getSessionRecorder()
               .writeIntBE( b );
        }
    }

    @Override
    public void writeBoolean( final boolean v )
    {
    }

    @Override
    public void writeByte( final int v )
        throws IOException
    {
        if ( canWrite() )
        {
            rfb.getSessionRecorder()
               .writeByte( v );
        }
    }

    @Override
    public void writeBytes( final String s )
    {
    }

    @Override
    public void writeChar( final int v )
    {
    }

    @Override
    public void writeChars( final String s )
    {
    }

    @Override
    public void writeDouble( final double v )
    {
    }

    @Override
    public void writeFloat( final float v )
    {
    }

    @Override
    public void writeInt( final int v )
        throws IOException
    {
        if ( canWrite() )
        {
            rfb.getSessionRecorder()
               .writeIntBE( v );
        }
    }

    @Override
    public void writeLong( final long v )
    {
    }

    @Override
    public void writeShort( final int v )
        throws IOException
    {
        if ( canWrite() )
        {
            rfb.getSessionRecorder()
               .writeShortBE( v );
        }
    }

    @Override
    public void writeUTF( final String str )
    {
    }

    private RfbProto rfb = null;
}
