/* Copyright (C) 2002-2004 RealVNC Ltd.  All Rights Reserved.
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
// zrleEncode.h - zrle encoding function.
//
// This file is #included after having set the following macros:
// BPP                - 8, 16 or 32
// EXTRA_ARGS         - optional extra arguments
// GET_IMAGE_INTO_BUF - gets a rectangle of pixel data into a buffer
//
// Note that the buf argument to ZRLE_ENCODE needs to be at least one pixel
// bigger than the largest tile of pixel data, since the ZRLE encoding
// algorithm writes to the position one past the end of the pixel data.
//

#include <rdr/OutStream.h>
#include <rdr/ZlibOutStream.h>
#include <assert.h>

namespace rfb {

// CONCAT2E concatenates its arguments, expanding them if they are macros

#ifndef CONCAT2E
#define CONCAT2(a,b) a##b
#define CONCAT2E(a,b) CONCAT2(a,b)
#endif

#ifdef CPIXEL
#define PIXEL_T rdr::CONCAT2E(U,BPP)
#define WRITE_PIXEL CONCAT2E(writeOpaque,CPIXEL)
#define ZRLE_ENCODE CONCAT2E(zrleEncode,CPIXEL)
#define ZRLE_ENCODE_TILE CONCAT2E(zrleEncodeTile,CPIXEL)
#define BPPOUT 24
#else
#define PIXEL_T rdr::CONCAT2E(U,BPP)
#define WRITE_PIXEL CONCAT2E(writeOpaque,BPP)
#define ZRLE_ENCODE CONCAT2E(zrleEncode,BPP)
#define ZRLE_ENCODE_TILE CONCAT2E(zrleEncodeTile,BPP)
#define BPPOUT BPP
#endif

#ifndef ZRLE_ONCE
#define ZRLE_ONCE
static const int bitsPerPackedPixel[] = {
  0, 1, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4
};

// The PaletteHelper class helps us build up the palette from pixel data by
// storing a reverse index using a simple hash-table

class PaletteHelper {
public:
  enum { MAX_SIZE = 127 };

  PaletteHelper()
  {
    memset(index, 255, sizeof(index));
    size = 0;
  }

  inline int hash(rdr::U32 pix)
  {
    return (pix ^ (pix >> 17)) & 4095;
  }

  inline void insert(rdr::U32 pix)
  {
    if (size < MAX_SIZE) {
      int i = hash(pix);
      while (index[i] != 255 && key[i] != pix)
        i++;
      if (index[i] != 255) return;

      index[i] = size;
      key[i] = pix;
      palette[size] = pix;
    }
    size++;
  }

  inline int lookup(rdr::U32 pix)
  {
    assert(size <= MAX_SIZE);
    int i = hash(pix);
    while (index[i] != 255 && key[i] != pix)
      i++;
    if (index[i] != 255) return index[i];
    return -1;
  }

  rdr::U32 palette[MAX_SIZE];
  rdr::U8 index[4096+MAX_SIZE];
  rdr::U32 key[4096+MAX_SIZE];
  int size;
};
#endif

void ZRLE_ENCODE_TILE (PIXEL_T* data, int w, int h, rdr::OutStream* os);

bool ZRLE_ENCODE (const Rect& r, rdr::OutStream* os,
                  rdr::ZlibOutStream* zos, void* buf, int maxLen, Rect* actual
#ifdef EXTRA_ARGS
                  , EXTRA_ARGS
#endif
                  )
{
  zos->setUnderlying(os);
  // RLE overhead is at worst 1 byte per 64x64 (4Kpixel) block
  int worstCaseLine = r.width() * 64 * (BPPOUT/8) + 1 + r.width() / 64;
  // Zlib overhead is at worst 6 bytes plus 5 bytes per 32Kbyte block.
  worstCaseLine += 11 + 5 * (worstCaseLine >> 15);
  Rect t;

  for (t.tl.y = r.tl.y; t.tl.y < r.br.y; t.tl.y += 64) {

    t.br.y = min(r.br.y, t.tl.y + 64);

    if (os->length() + worstCaseLine > maxLen) {
      if (t.tl.y == r.tl.y)
        throw Exception("ZRLE: not enough space for first line?");
      actual->tl = r.tl;
      actual->br.x = r.br.x;
      actual->br.y = t.tl.y;
      return false;
    }

    for (t.tl.x = r.tl.x; t.tl.x < r.br.x; t.tl.x += 64) {

      t.br.x = min(r.br.x, t.tl.x + 64);

      GET_IMAGE_INTO_BUF(t,buf);

      ZRLE_ENCODE_TILE((PIXEL_T*)buf, t.width(), t.height(), zos);
    }

    zos->flush();
  }
  return true;
}


void ZRLE_ENCODE_TILE (PIXEL_T* data, int w, int h, rdr::OutStream* os)
{
  // First find the palette and the number of runs

  PaletteHelper ph;

  int runs = 0;
  int singlePixels = 0;

  PIXEL_T* ptr = data;
  PIXEL_T* end = ptr + h * w;
  *end = ~*(end-1); // one past the end is different so the while loop ends

  while (ptr < end) {
    PIXEL_T pix = *ptr;
    if (*++ptr != pix) {
      singlePixels++;
    } else {
      while (*++ptr == pix) ;
      runs++;
    }
    ph.insert(pix);
  }

  //fprintf(stderr,"runs %d, single pixels %d, paletteSize %d\n",
  //        runs, singlePixels, ph.size);

  // Solid tile is a special case

  if (ph.size == 1) {
    os->writeU8(1);
    os->WRITE_PIXEL(ph.palette[0]);
    return;
  }

  // Try to work out whether to use RLE and/or a palette.  We do this by
  // estimating the number of bytes which will be generated and picking the
  // method which results in the fewest bytes.  Of course this may not result
  // in the fewest bytes after compression...

  bool useRle = false;
  bool usePalette = false;

  int estimatedBytes = w * h * (BPPOUT/8); // start assuming raw

  int plainRleBytes = ((BPPOUT/8)+1) * (runs + singlePixels);

  if (plainRleBytes < estimatedBytes) {
    useRle = true;
    estimatedBytes = plainRleBytes;
  }

  if (ph.size < 128) {
    int paletteRleBytes = (BPPOUT/8) * ph.size + 2 * runs + singlePixels;

    if (paletteRleBytes < estimatedBytes) {
      useRle = true;
      usePalette = true;
      estimatedBytes = paletteRleBytes;
    }

    if (ph.size < 17) {
      int packedBytes = ((BPPOUT/8) * ph.size +
                         w * h * bitsPerPackedPixel[ph.size-1] / 8);

      if (packedBytes < estimatedBytes) {
        useRle = false;
        usePalette = true;
        estimatedBytes = packedBytes;
      }
    }
  }

  if (!usePalette) ph.size = 0;

  os->writeU8((useRle ? 128 : 0) | ph.size);

  for (int i = 0; i < ph.size; i++) {
    os->WRITE_PIXEL(ph.palette[i]);
  }

  if (useRle) {

    PIXEL_T* ptr = data;
    PIXEL_T* end = ptr + w * h;
    PIXEL_T* runStart;
    PIXEL_T pix;
    while (ptr < end) {
      runStart = ptr;
      pix = *ptr++;
      while (*ptr == pix && ptr < end)
        ptr++;
      int len = ptr - runStart;
      if (len <= 2 && usePalette) {
        int index = ph.lookup(pix);
        if (len == 2)
          os->writeU8(index);
        os->writeU8(index);
        continue;
      }
      if (usePalette) {
        int index = ph.lookup(pix);
        os->writeU8(index | 128);
      } else {
        os->WRITE_PIXEL(pix);
      }
      len -= 1;
      while (len >= 255) {
        os->writeU8(255);
        len -= 255;
      }
      os->writeU8(len);
    }

  } else {

    // no RLE

    if (usePalette) {

      // packed pixels

      assert (ph.size < 17);

      int bppp = bitsPerPackedPixel[ph.size-1];

      PIXEL_T* ptr = data;

      for (int i = 0; i < h; i++) {
        rdr::U8 nbits = 0;
        rdr::U8 byte = 0;

        PIXEL_T* eol = ptr + w;

        while (ptr < eol) {
          PIXEL_T pix = *ptr++;
          rdr::U8 index = ph.lookup(pix);
          byte = (byte << bppp) | index;
          nbits += bppp;
          if (nbits >= 8) {
            os->writeU8(byte);
            nbits = 0;
          }
        }
        if (nbits > 0) {
          byte <<= 8 - nbits;
          os->writeU8(byte);
        }
      }
    } else {

      // raw

#ifdef CPIXEL
      for (PIXEL_T* ptr = data; ptr < data+w*h; ptr++) {
        os->WRITE_PIXEL(*ptr);
      }
#else
      os->writeBytes(data, w*h*(BPP/8));
#endif
    }
  }
}

#undef PIXEL_T
#undef WRITE_PIXEL
#undef ZRLE_ENCODE
#undef ZRLE_ENCODE_TILE
#undef BPPOUT
}
