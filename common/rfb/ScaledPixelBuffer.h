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

// -=- ScaledPixelBuffer.h
//
// The ScaledPixelBuffer class allows to scale the image data 
// from the source buffer to destination buffer using bilinear 
// interpolation.

#include <rdr/types.h>
#include <rdr/Exception.h>
#include <rfb/Rect.h>
#include <rfb/PixelFormat.h>
#include <rfb/ScaleFilters.h>

using namespace rdr;

namespace rfb {

  struct UnsupportedPixelFormatException : public Exception {
    UnsupportedPixelFormatException(const char* s="Now supported only true colour pixel data in the scaling mode.")
      : Exception(s) {}
  };

  class ScaledPixelBuffer {
  public:
    ScaledPixelBuffer(U8 **data, int width, int height, int scale, PixelFormat pf);
    ScaledPixelBuffer();
    virtual ~ScaledPixelBuffer();

    // Get width, height, number of pixels and scale
    int width()  const { return scaled_width; }
    int height() const { return scaled_height; }
    int getSrcWidth()  const { return src_width; }
    int getSrcHeight() const { return src_height; }
    int area() const { return scaled_width * scaled_height; }
    int getScale() const { return int(scale_ratio * 100 + 0.5); }
    double getScaleRatio() const { return scale_ratio; }

    // Pixel manipulation routines
    inline U32 getSourcePixel(int x, int y);
    inline void rgbFromPixel(U32 p, int &r, int &g, int &b);

    // Get rectangle encompassing this buffer
    //   Top-left of rectangle is either at (0,0), or the specified point.
    Rect getRect() const { return Rect(0, 0, scaled_width, scaled_height); }
    Rect getRect(const Point& pos) const {
      return Rect(pos, pos.translate(Point(scaled_width, scaled_height)));
    }

    // Set the new source buffer and its parameters
    void setSourceBuffer(U8 **src_data, int w, int h);

    void setScaledBuffer(U8 **scaled_data_) {
      scaled_data = scaled_data_;
    };

    // Set the new pixel format
    void setPF(const PixelFormat &pf);

    // Set the new scale, in percent
    virtual void setScale(int scale) { setScaleRatio(double(scale)/100.0); }
    virtual void setScaleRatio(double scale_ratio);

    // Scale rect from the source image buffer to the destination buffer
    // using bilinear interpolation
    virtual void scaleRect(const Rect& r);

    // Calculate the scaled image rectangle which depend on the source 
    // image rectangle.
    inline Rect calculateScaleBoundary(const Rect& r);

  protected:

    // Calculate the scaled buffer size depending on the source buffer
    // parameters (width, height, pixel format)
    virtual void calculateScaledBufferSize();

    // Free the weight tabs for x and y 
    virtual void freeWeightTabs();


    int src_width;
    int src_height;
    int scaled_width;
    int scaled_height;
    PixelFormat pf;
    double scale_ratio;
    unsigned int scaleFilterID;
    ScaleFilters scaleFilters;
    SFilterWeightTab *xWeightTabs;
    SFilterWeightTab *yWeightTabs;
    U8 **src_data;
    U8 **scaled_data;
  };

};
