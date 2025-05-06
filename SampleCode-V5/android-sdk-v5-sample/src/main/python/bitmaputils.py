import numpy as np
import cv2

def bitmap_to_cv2(bitmap):
    """
    Convert an Android Bitmap object to an OpenCV image (numpy array)
    
    Args:
        bitmap: Android Bitmap object
    
    Returns:
        numpy.ndarray: OpenCV format image
    """
    # Get bitmap info
    width = bitmap.getWidth()
    height = bitmap.getHeight()
    
    # Create a numpy array to hold the pixel data
    pixels = np.zeros((height, width, 4), dtype=np.uint8)
    
    # Create a 1D array to store the pixels temporarily
    # This makes the conversion much faster than accessing pixels individually
    buffer = np.zeros(width * height * 4, dtype=np.uint8)
    
    # Get pixels to buffer
    bitmap.copyPixelsToBuffer(buffer)
    
    # Reshape to 2D array with 4 channels (RGBA)
    pixels = buffer.reshape(height, width, 4)
    
    # Convert from RGBA to BGR (which is what OpenCV uses)
    # Drop the alpha channel
    cv2_image = cv2.cvtColor(pixels, cv2.COLOR_RGBA2BGR)
    
    return cv2_image