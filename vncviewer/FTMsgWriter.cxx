/* Copyright (C) 2005 TightVNC Team.  All Rights Reserved.
 *    
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 *
 * TightVNC distribution homepage on the Web: http://www.tightvnc.com/
 *
 */

// -=- FTMsgWriter.cxx

#include <vncviewer/FTMsgWriter.h>

using namespace rfb;
using namespace rfb::win32;

FTMsgWriter::FTMsgWriter(rdr::OutStream *pOS)
{
  m_pOutStream = pOS;
}

FTMsgWriter::~FTMsgWriter()
{
}

bool 
FTMsgWriter::writeFileListRqst(unsigned short dirNameSize, char *pDirName,
                               int dest, unsigned char flags)
{
  return false;
}


bool 
FTMsgWriter::writeFileDownloadCancel(unsigned short reasonLen, char *pReason)
{
  return false;
}

bool 
FTMsgWriter::writeFileDownloadRqst(unsigned short filenameLen, char *pFilename, 
                                   unsigned int position)
{
  return false;
}

bool 
FTMsgWriter::writeFileUploadData(unsigned short dataSize, char *pData)
{
  return false;
}

bool 
FTMsgWriter::writeFileUploadData(unsigned int modTime)
{
  return false;
}

bool 
FTMsgWriter::writeFileUploadFailed(unsigned short reasonLen, char *pReason)
{
  return false;
}

bool 
FTMsgWriter::writeFileUploadRqst(unsigned short filenameLen, char *pFilename, 
                                 unsigned int position)
{
  return false;
}

bool 
FTMsgWriter::writeFileCreateDirRqst(unsigned short dirNameLen, char *pDirName)
{
  return false;
}

bool 
FTMsgWriter::writeFileDirSizeRqst(unsigned short dirNameLen, char *pDirName, int dest)
{
  return false;
}

bool 
FTMsgWriter::writeFileRenameRqst(unsigned short oldNameLen, unsigned short newNameLen,
                                 char *pOldName, char *pNewName)
{
  return false;
}

bool 
FTMsgWriter::writeFileDeleteRqst(unsigned short nameLen, char *pName)
{
  return false;
}