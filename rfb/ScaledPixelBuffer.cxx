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
 */

// -=- ScaledPixelBuffer.cxx

#include <rfb/ScaledPixelBuffer.h>

#include <math.h>
#include <memory.h>

using namespace rdr;
using namespace rfb;

ScaledPixelBuffer::ScaledPixelBuffer(U8 *src_data_, int src_width_,
                                     int src_height_, int scale)
  : src_data(src_data_), src_width(src_width_), src_height(src_height_),
    bpp(32), data(0) {

  scale_ratio = double(scale) / 100;

  width_  = (int)ceil(src_width  * scale_ratio);
  height_ = (int)ceil(src_height * scale_ratio);
  
  data = new U8[width_ * height_ * 4];
}

ScaledPixelBuffer::~ScaledPixelBuffer() {
  delete [] data;
}

const U8* ScaledPixelBuffer::getPixelsR(const Rect& r, int* stride) {
  *stride = getStride();
  return &data[(r.tl.x + (r.tl.y * *stride)) * bpp/8];
}

void ScaledPixelBuffer::getImage(void* imageBuf, const Rect& r, int outStride) {
  int inStride;
  const U8* pixels_data = getPixelsR(r, &inStride);
  // We assume that the specified rectangle is pre-clipped to the buffer
  int bytesPerPixel = bpp/8;
  int inBytesPerRow = inStride * bytesPerPixel;
  if (!outStride) outStride = r.width();
  int outBytesPerRow = outStride * bytesPerPixel;
  int bytesPerMemCpy = r.width() * bytesPerPixel;
  U8* imageBufPos = (U8*)imageBuf;
  const U8* end = pixels_data + (inBytesPerRow * r.height());
  while (pixels_data < end) {
    memcpy(imageBufPos, pixels_data, bytesPerMemCpy);
    imageBufPos += outBytesPerRow;
    pixels_data += inBytesPerRow;
  }
} 

void ScaledPixelBuffer::setScale(int scale) {
  if (scale != scale_ratio * 100) {
    scale_ratio = double(scale) / 100;

    width_  = (int)ceil(src_width  * scale_ratio);
    height_ = (int)ceil(src_height * scale_ratio);

    delete [] data;
    data = new U8[width_ * height_ * 4];

    scaleRect(Rect(0, 0, width_, height_));
  }
}

void ScaledPixelBuffer::scaleRect(const Rect& r) {
  static U8 *src_ptr, *ptr;
  static U8 r0, r1, r2, r3;
  static U8 g0, g1, g2, g3;
  static U8 b0, b1, b2, b3;
  static double c1_sub_dx, c1_sub_dy;
  static double x_start, x_end, y_start, y_end;
  static double dx, dy;
  static int i, j;

  // Calculate the scale boundaries
  x_start = vncmax(0, (r.tl.x-1) * scale_ratio);
  (x_start==int(x_start)) ? true : x_start=(int)(x_start+1);
  x_end = vncmin(width_ - 1, r.br.x * scale_ratio);
  ((x_end==int(x_end))&&(x_end!=width_-1)&&(x_end>0)) ? x_end-=1:x_end=(int)(x_end);
  y_start = vncmax(0, (r.tl.y-1) * scale_ratio);
  (y_start==int(y_start)) ? true : y_start=(int)(y_start+1);
  y_end = vncmin(height_ - 1, r.br.y * scale_ratio);
  ((y_end==int(y_end))&&(y_end!=height_-1)&&(y_end>0)) ? y_end-=1:y_end=(int)(y_end);

  // Scale the source rect to the destination image buffer using
  // bilinear interplation
  for (int y = (int)y_start; y <= y_end; y++) {
    j = (int)(dy = y / scale_ratio);
    dy -= j;
    c1_sub_dy = 1 - dy;

    for (int x = (int)x_start; x <= x_end; x++) {
      ptr = &data[(x + y*width_) * 4];

      i = (int)(dx = x / scale_ratio);
      dx -= i;
      c1_sub_dx = 1 - dx;

      src_ptr = &src_data[(i + (j*src_width))*4];
      b0 = *src_ptr; g0 = *(src_ptr+1); r0 = *(src_ptr+2);
      if (i+1 < src_width) {
        b1 = *(src_ptr+4); g1 = *(src_ptr+5); r1 = *(src_ptr+6);
      } else {
        b1 = b0; r1 = r0; g1 = g0;
      }
      if (j+1 < src_height) {
        src_ptr += src_width * 4;
        b3 = *src_ptr; g3 = *(src_ptr+1); r3 = *(src_ptr+2);
      } else {
        b3 = b0; r3 = r0; g3 = g0;
      }
      if ((i+1 < src_width) && (j+1 < src_height)) {
        b2 = *(src_ptr+4); g2 = *(src_ptr+5); r2 = *(src_ptr+6);
      } else if (i+1 >= src_width) {
        b2 = b3; r2 = r3; g2 = g3;
      } else {
        b2 = b1; r2 = r1; g2 = g1;
      }
      *ptr++ = (U8)((b0*c1_sub_dx+b1*dx)*c1_sub_dy + (b3*c1_sub_dx+b2*dx)*dy);
      *ptr++ = (U8)((g0*c1_sub_dx+g1*dx)*c1_sub_dy + (g3*c1_sub_dx+g2*dx)*dy);
      *ptr   = (U8)((r0*c1_sub_dx+r1*dx)*c1_sub_dy + (r3*c1_sub_dx+r2*dx)*dy);
    }
  }
}