/* Copyright (C) 2004-2007 Constantin Kaplinsky.  All Rights Reserved.
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
 */
//
// PollingManager.cxx
//

#include <stdio.h>
#include <string.h>
#include <time.h>
#include <X11/Xlib.h>
#include <rfb/LogWriter.h>
#include <rfb/VNCServer.h>
#include <rfb/Configuration.h>
#include <rfb/ServerCore.h>

#include <x0vncserver/PollingManager.h>

static LogWriter vlog("PollingMgr");

const int PollingManager::m_pollingOrder[32] = {
   0, 16,  8, 24,  4, 20, 12, 28,
  10, 26, 18,  2, 22,  6, 30, 14,
   1, 17,  9, 25,  7, 23, 15, 31,
  19,  3, 27, 11, 29, 13,  5, 21
};

IntParameter PollingManager::m_videoPriority("VideoPriority",
  "Priority of sending updates for video area (0..8)", 2);

//
// Constructor.
//
// Note that dpy and image should remain valid during the object
// lifetime, while factory is used only in the constructor itself.
//

PollingManager::PollingManager(Display *dpy, Image *image,
                               ImageFactory *factory,
                               int offsetLeft, int offsetTop)
  : m_dpy(dpy), m_server(0), m_image(image),
    m_offsetLeft(offsetLeft), m_offsetTop(offsetTop),
    m_numVideoPasses(0),
    m_pollingStep(0)
{
  // Save width and height of the screen (and the image).
  m_width = m_image->xim->width;
  m_height = m_image->xim->height;

  // Compute width and height in 32x32 tiles.
  m_widthTiles = (m_width + 31) / 32;
  m_heightTiles = (m_height + 31) / 32;

  // Get initial screen image.
  m_image->get(DefaultRootWindow(m_dpy), m_offsetLeft, m_offsetTop);

  // Create additional images used in polling algorithm, warn if
  // underlying class names are different from the class name of the
  // primary image.
  m_rowImage = factory->newImage(m_dpy, m_width, 1);
  if (strcmp(m_image->className(), m_rowImage->className()) != 0) {
    vlog.error("Image types do not match (%s)",
               m_rowImage->className());
  }

  int numTiles = m_widthTiles * m_heightTiles;
  m_rateMatrix = new char[numTiles];
  m_videoFlags = new char[numTiles];
  memset(m_rateMatrix, 0, numTiles);
  memset(m_videoFlags, 0, numTiles);
}

PollingManager::~PollingManager()
{
  delete[] m_videoFlags;
  delete[] m_rateMatrix;

  delete m_rowImage;
}

//
// Register VNCServer object.
//

void PollingManager::setVNCServer(VNCServer *s)
{
  m_server = s;
}

//
// DEBUG: Measuring time spent in the poll() function,
//        as well as time intervals between poll() calls.
//

#ifdef DEBUG
void PollingManager::debugBeforePoll()
{
  TimeMillis timeNow;
  int diff = timeNow.diffFrom(m_timeSaved);
  fprintf(stderr, "[wait%4dms]\t[step %2d]\t", diff, m_pollingStep % 32);
  m_timeSaved = timeNow;
}

void PollingManager::debugAfterPoll()
{
  TimeMillis timeNow;
  int diff = timeNow.diffFrom(m_timeSaved);
  fprintf(stderr, "[poll%4dms]\n", diff);
  m_timeSaved = timeNow;
}

#endif

//
// Search for changed rectangles on the screen.
//

void PollingManager::poll()
{
#ifdef DEBUG
  debugBeforePoll();
#endif

  // Perform polling and try update clients if changes were detected.
  if (pollScreen())
    m_server->tryUpdate();

#ifdef DEBUG
  debugAfterPoll();
#endif
}

bool PollingManager::pollScreen()
{
  if (!m_server)
    return false;

  // If video data should have higher priority, and video area was
  // detected, perform special passes to send video data only. Such
  // "video passes" will be performed between normal polling passes.
  // No actual polling is performed in a video pass since we know that
  // video is changing continuously.
  if ((int)m_videoPriority > 1 && !m_videoRect.is_empty()) {
    if (m_numVideoPasses > 0) {
      m_numVideoPasses--;
      getScreenRect(m_videoRect);
      return true;              // we've got changes
    } else {
      // Normal pass now, but schedule video passes for next calls.
      m_numVideoPasses = (int)m_videoPriority - 1;
    }
  }

  // changeFlags[] array will hold boolean values corresponding to
  // each 32x32 tile. If a value is true, then we've detected a change
  // in that tile. Initially, we fill in the array with zero values.
  bool *changeFlags = new bool[m_widthTiles * m_heightTiles];
  memset(changeFlags, 0, m_widthTiles * m_heightTiles * sizeof(bool));

  // First pass over the framebuffer. Here we scan 1/32 part of the
  // framebuffer -- that is, one line in each (32 * m_width) stripe.
  // We compare the pixels of that line with previous framebuffer
  // contents and raise corresponding member values of changeFlags[].
  int scanOffset = m_pollingOrder[m_pollingStep++ % 32];
  bool *pChangeFlags = changeFlags;
  int nTilesChanged = 0;
  for (int y = scanOffset; y < m_height; y += 32) {
    nTilesChanged += checkRow(0, y, m_width, pChangeFlags);
    pChangeFlags += m_widthTiles;
  }

  // Do the work related to video area detection.
  bool haveVideoRect = false;
  if ((int)m_videoPriority != 0)
    haveVideoRect = handleVideo(changeFlags);

  // Inform the server about the changes.
  // FIXME: It's possible that (nTilesChanged != 0) but changeFlags[]
  //        array is empty. That's possible because handleVideo()
  //        modifies changeFlags[].
  if (nTilesChanged)
    sendChanges(changeFlags);

  // Cleanup.
  delete[] changeFlags;

#ifdef DEBUG
  if (nTilesChanged != 0) {
    fprintf(stderr, "#%d# ", nTilesChanged);
  }
#endif

  return (nTilesChanged != 0 || haveVideoRect);
}

int PollingManager::checkRow(int x, int y, int w, bool *pChangeFlags)
{
  int bytesPerPixel = m_image->xim->bits_per_pixel / 8;
  int bytesPerLine = m_image->xim->bytes_per_line;

  if (x == 0 && w == m_width) {
    getFullRow(y);              // use more efficient method if possible
  } else {
    getRow(x, y, w);
  }

  char *ptr_old = m_image->xim->data + y * bytesPerLine + x * bytesPerPixel;
  char *ptr_new = m_rowImage->xim->data;

  int nTilesChanged = 0;
  for (int i = 0; i < (w + 31) / 32; i++) {
    int tile_w = (w - i * 32 >= 32) ? 32 : w - i * 32;
    int nBytes = tile_w * bytesPerPixel;
    if (memcmp(ptr_old, ptr_new, nBytes)) {
      *pChangeFlags = true;
      nTilesChanged++;
    }
    pChangeFlags++;
    ptr_old += nBytes;
    ptr_new += nBytes;
  }

  return nTilesChanged;
}

void PollingManager::sendChanges(bool *pChangeFlags)
{
  Rect rect;
  for (int y = 0; y < m_heightTiles; y++) {
    for (int x = 0; x < m_widthTiles; x++) {
      if (*pChangeFlags++) {
        // Count successive tiles marked as changed.
        int count = 1;
        while (x + count < m_widthTiles && *pChangeFlags++) {
          count++;
        }
        // Compute the coordinates and the size of this band.
        rect.setXYWH(x * 32, y * 32, count * 32, 32);
        if (rect.br.x > m_width)
          rect.br.x = m_width;
        if (rect.br.y > m_height)
          rect.br.y = m_height;
        // Add to the changed region maintained by the server.
        getScreenRect(rect);
        m_server->add_changed(rect);
        // Skip processed tiles.
        x += count;
      }
    }
  }
}

bool PollingManager::handleVideo(bool *pChangeFlags)
{
  // Update counters in m_rateMatrix.
  int numTiles = m_heightTiles * m_widthTiles;
  for (int i = 0; i < numTiles; i++)
    m_rateMatrix[i] += (pChangeFlags[i] != false);

  // Once per eight calls: detect video rectangle by examining
  // m_rateMatrix[], then reset counters in m_rateMatrix[].
  if (m_pollingStep % 8 == 0) {
    detectVideo();
    memset(m_rateMatrix, 0, numTiles);
  }

  // Grab the pixels of video area. Also, exclude video rectangle from
  // pChangeFlags[], to prevent grabbing the same pixels twice.
  if (!m_videoRect.is_empty()) {
    Rect r(m_videoRect.tl.x / 32, m_videoRect.tl.y / 32,
           m_videoRect.br.x / 32, m_videoRect.br.y / 32);
    for (int y = r.tl.y; y < r.br.y; y++) {
      for (int x = r.tl.x; x < r.br.x; x++) {
        pChangeFlags[y * m_widthTiles + x] = false;
      }
    }
    getScreenRect(m_videoRect);
    return true;                // we've got a video rectangle
  }

  return false;                 // video rectangle is empty
}

void
PollingManager::detectVideo()
{
  // Configurable parameters.
  const int VIDEO_THRESHOLD_0 = 3;
  const int VIDEO_THRESHOLD_1 = 5;

  // In m_rateMatrix, clear counters corresponding to non-32x32 tiles.
  // This will guarantee that the size of the video area is always a
  // multiple of 32 pixels. This is important for hardware JPEG encoders.
  int numTiles = m_heightTiles * m_widthTiles;
  if (m_width % 32 != 0) {
    for (int n = m_widthTiles - 1; n < numTiles; n += m_widthTiles)
      m_rateMatrix[n] = 0;
  }
  if (m_height % 32 != 0) {
    for (int n = numTiles - m_widthTiles; n < numTiles; n++)
      m_rateMatrix[n] = 0;
  }

  // First, detect candidate region that looks like video. In other
  // words, find a region that consists of continuously changing
  // pixels. Save the result in m_videoFlags[].
  for (int i = 0; i < numTiles; i++) {
    if (m_rateMatrix[i] <= VIDEO_THRESHOLD_0) {
      m_videoFlags[i] = 0;
    } else if (m_rateMatrix[i] >= VIDEO_THRESHOLD_1) {
      m_videoFlags[i] = 1;
    }
  }

  // Now, choose the biggest rectangle from that candidate region.
  Rect newRect;
  getVideoAreaRect(&newRect);

  // Does new rectangle differ from the previously detected one?
  // If it does, save new rectangle and inform the server.
  if (!newRect.equals(m_videoRect)) {
    if (newRect.is_empty()) {
      vlog.debug("No video detected");
    } else {
      vlog.debug("Detected video %dx%d at (%d,%d)",
                 newRect.width(), newRect.height(),
                 newRect.tl.x, newRect.tl.y);
    }
    m_videoRect = newRect;
    m_server->set_video_area(newRect);
  }
}

void
PollingManager::getVideoAreaRect(Rect *result)
{
  int *mx_hlen, *mx_vlen;
  constructLengthMatrices(&mx_hlen, &mx_vlen);

  int full_h = m_heightTiles;
  int full_w = m_widthTiles;
  int x, y;
  Rect max_rect(0, 0, 0, 0);
  Rect local_rect;

  for (y = 0; y < full_h; y++) {
    for (x = 0; x < full_w; x++) {
      int max_w = mx_hlen[y * full_w + x];
      int max_h = mx_vlen[y * full_w + x];
      if (max_w > 2 && max_h > 1 && max_h * max_w > (int)max_rect.area()) {
        local_rect.tl.x = x;
        local_rect.tl.y = y;
        findMaxLocalRect(&local_rect, mx_hlen, mx_vlen);
        if (local_rect.area() > max_rect.area()) {
          max_rect = local_rect;
        }
      }
    }
  }

  destroyLengthMatrices(mx_hlen, mx_vlen);

  max_rect.tl.x *= 32;
  max_rect.tl.y *= 32;
  max_rect.br.x *= 32;
  max_rect.br.y *= 32;
  if (max_rect.br.x > m_width)
    max_rect.br.x = m_width;
  if (max_rect.br.y > m_height)
    max_rect.br.y = m_height;
  *result = max_rect;
}

void
PollingManager::constructLengthMatrices(int **pmx_h, int **pmx_v)
{
  // Handy shortcuts.
  int h = m_heightTiles;
  int w = m_widthTiles;

  // Allocate memory.
  int *mx_h = new int[h * w];
  memset(mx_h, 0, h * w * sizeof(int));
  int *mx_v = new int[h * w];
  memset(mx_v, 0, h * w * sizeof(int));

  int x, y, len, i;

  // Fill in horizontal length matrix.
  for (y = 0; y < h; y++) {
    for (x = 0; x < w; x++) {
      len = 0;
      while (x + len < w && m_videoFlags[y * w + x + len]) {
        len++;
      }
      for (i = 0; i < len; i++) {
        mx_h[y * w + x + i] = len - i;
      }
      x += len;
    }
  }

  // Fill in vertical length matrix.
  for (x = 0; x < w; x++) {
    for (y = 0; y < h; y++) {
      len = 0;
      while (y + len < h && m_videoFlags[(y + len) * w + x]) {
        len++;
      }
      for (i = 0; i < len; i++) {
        mx_v[(y + i) * w + x] = len - i;
      }
      y += len;
    }
  }

  *pmx_h = mx_h;
  *pmx_v = mx_v;
}

void
PollingManager::destroyLengthMatrices(int *mx_h, int *mx_v)
{
  delete[] mx_h;
  delete[] mx_v;
}

// NOTE: This function assumes that current tile has non-zero in mx_h[],
//       otherwise we get division by zero.
void
PollingManager::findMaxLocalRect(Rect *r, int mx_h[], int mx_v[])
{
  int idx = r->tl.y * m_widthTiles + r->tl.x;

  // NOTE: Rectangle's maximum width and height are 25 and 18
  //       (in tiles, where each tile is usually 32x32 pixels).
  int max_w = mx_h[idx];
  if (max_w > 25)
    max_w = 25;
  int cur_h = 18;

  int best_w = max_w;
  int best_area = 1 * best_w;

  for (int i = 0; i < max_w; i++) {
    int h = mx_v[idx + i];
    if (h < cur_h) {
      cur_h = h;
      if (cur_h * max_w <= best_area)
        break;
    }
    if (cur_h * (i + 1) > best_area) {
      best_w = i + 1;
      best_area = cur_h * best_w;
    }
  }

  r->br.x = r->tl.x + best_w;
  r->br.y = r->tl.y + best_area / best_w;
}

