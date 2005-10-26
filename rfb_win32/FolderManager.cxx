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

// -=- FolderManager.cxx

#include <rfb_win32/FolderManager.h>

using namespace rfb;
using namespace rfb::win32;

FolderManager::FolderManager()
{

}

FolderManager::~FolderManager()
{

}
      
bool 
FolderManager::createDir(char *pFullPath)
{
  if (CreateDirectory(pFullPath, NULL) == 0) return false;

  return true;
}

bool 
FolderManager::renameIt(char *pOldName, char *pNewName)
{
  if (MoveFile(pOldName, pNewName)) return true;

  return false;
}

bool 
FolderManager::deleteIt(char *pFullPath)
{
  FileInfo fileInfo;

  FILEINFO FIStruct;
  if (!getInfo(pFullPath, &FIStruct)) return false;

  fileInfo.add(&FIStruct);

  unsigned int num = fileInfo.getNumEntries();
  unsigned int last = num - 1;

  while (num > 0) {
    if (fileInfo.getFlagsAt(last) & FT_ATTR_DIR) {
      if (RemoveDirectory(fileInfo.getNameAt(last)) == 0) {
        if (GetLastError() == ERROR_DIR_NOT_EMPTY) {
          if (!getFolderInfoWithPrefix(fileInfo.getNameAt(last), &fileInfo)) {
            fileInfo.free();
            return false;
          }
        }
      } else {
        fileInfo.deleteAt(last);
      }
    } else {
      if (DeleteFile(fileInfo.getNameAt(last)) == 0) {
        fileInfo.free();
        return false;
      } else {
        fileInfo.deleteAt(last);
      }
    }

    num = fileInfo.getNumEntries();
    last = num - 1;
  }

  return true;
}

bool 
FolderManager::getFolderInfoWithPrefix(char *pPrefix, FileInfo *pFileInfo)
{
  char prefix[FT_FILENAME_SIZE];
  strcpy(prefix, pPrefix);

  FileInfo tmpFileInfo;
  if (!getFolderInfo(prefix, &tmpFileInfo, 0)) {
    tmpFileInfo.free();
    return false;
  } else {
    char buf[FT_FILENAME_SIZE];
    for (unsigned int i = 0; i < tmpFileInfo.getNumEntries(); i++) {
      sprintf(buf, "%s\\%s", prefix, tmpFileInfo.getNameAt(i));
      pFileInfo->add(buf, tmpFileInfo.getSizeAt(i), tmpFileInfo.getDataAt(i), tmpFileInfo.getFlagsAt(i));
    }
  }
  tmpFileInfo.free();
  return true;
}
    
bool 
FolderManager::getFolderInfo(char *pPath, FileInfo *pFileInfo, unsigned int dirOnly)
{
	if (strlen(pPath) == 0) return getDrivesInfo(pFileInfo);

	char path[FT_FILENAME_SIZE];
	sprintf(path, "%s\\*", pPath);

	WIN32_FIND_DATA FindFileData;
	SetErrorMode(SEM_FAILCRITICALERRORS);
	HANDLE handle = FindFirstFile(path, &FindFileData);
	DWORD lastError = GetLastError();
	SetErrorMode(0);

	if (handle != INVALID_HANDLE_VALUE) {
		do {
			if (strcmp(FindFileData.cFileName, ".") != 0 &&
				strcmp(FindFileData.cFileName, "..") != 0) {
                unsigned int lastWriteTime = getTime70(FindFileData.ftLastWriteTime);
				if ((FindFileData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {	
					pFileInfo->add(FindFileData.cFileName, 0, lastWriteTime, FT_ATTR_DIR);
				} else {
					if (!dirOnly)
						pFileInfo->add(FindFileData.cFileName, FindFileData.nFileSizeLow, lastWriteTime, FT_ATTR_FILE);
				}
			}
			
		} while (FindNextFile(handle, &FindFileData));
	} else {
		return false;
	}
	FindClose(handle);
	return true;
}

bool 
FolderManager::getDrivesInfo(FileInfo *pFileInfo)
{
	TCHAR szDrivesList[256];
	if (GetLogicalDriveStrings(255, szDrivesList) == 0)
		return false;

	int i = 0;
	while (szDrivesList[i] != '\0') {
		char *drive = strdup(&szDrivesList[i]);
		char *backslash = strrchr(drive, '\\');
		if (backslash != NULL)
			*backslash = '\0';
		pFileInfo->add(drive, 0, 0, FT_ATTR_DIR);
		free(drive);
		i += strcspn(&szDrivesList[i], "\0") + 1;
	}
	return true;
}

bool
FolderManager::getInfo(char *pFullPath, FILEINFO *pFIStruct)
{
	WIN32_FIND_DATA FindFileData;
	SetErrorMode(SEM_FAILCRITICALERRORS);
	HANDLE hFile = FindFirstFile(pFullPath, &FindFileData);
	DWORD lastError = GetLastError();
	SetErrorMode(0);
	if (hFile != INVALID_HANDLE_VALUE) {
		FindClose(hFile);
		strcpy(pFIStruct->name, FindFileData.cFileName);
		pFIStruct->info.data = getTime70(FindFileData.ftLastWriteTime);
		if (FindFileData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {	
			pFIStruct->info.size = 0;
			pFIStruct->info.flags = FT_ATTR_DIR;
			return true;
		} else {
			pFIStruct->info.size = FindFileData.nFileSizeLow;
			pFIStruct->info.flags = FT_ATTR_FILE;
			return true;
		}
	}
	return false;
}

unsigned int 
FolderManager::getTime70(FILETIME ftime)
{
	LARGE_INTEGER uli;
	uli.LowPart = ftime.dwLowDateTime;
	uli.HighPart = ftime.dwHighDateTime;
	uli.QuadPart = (uli.QuadPart - 116444736000000000) / 10000000;
	return uli.LowPart;
}

void 
FolderManager::getFiletime(unsigned int time70, FILETIME *pftime)
{
    LONGLONG ll = Int32x32To64(time70, 10000000) + 116444736000000000;
    pftime->dwLowDateTime = (DWORD) ll;
    pftime->dwHighDateTime = (DWORD) (ll >> 32);
}