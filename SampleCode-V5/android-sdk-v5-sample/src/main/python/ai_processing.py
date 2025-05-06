import cv2
import time
import base64
import os
from openai import OpenAI
from dotenv import load_dotenv
from ultralytics import YOLO

# Load environment variables from .env file
load_dotenv()

# --- Configuration ---
# Make sure to set the OPENAI_API_KEY environment variable
# You can get one from https://platform.openai.com/account/api-keys
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
# What do you want to ask the AI about the image?
OPENAI_PROMPT = os.getenv("OPENAI_PROMPT", "What is in this image?") # Default prompt if not set
# OpenAI Analysis Condition
OPENAI_CONDITION_PERSON_STR = os.getenv("OPENAI_CONDITION_PERSON", "true") # Default to true

OPENAI_CONDITION_PERSON = OPENAI_CONDITION_PERSON_STR.lower() == 'true'

if not OPENAI_API_KEY:
    print("Error: OPENAI_API_KEY environment variable not set.")
    exit()

client = OpenAI(api_key=OPENAI_API_KEY)

# --- YOLO Setup ---
# Load a pretrained YOLO model globally to avoid reloading on each call
# Ensure 'yolov8n.pt' (or your chosen model) is accessible
try:
    yolo_model = YOLO("yolov8n.pt") # Using yolov8n.pt as a common default, adjust if needed
except Exception as e:
    print(f"Error loading YOLO model: {e}")
    print("Please ensure the YOLO model file (e.g., 'yolov8n.pt') is available.")
    exit()

def encode_image_to_base64(image_frame):
    """Encodes a numpy array image to a base64 string."""
    _, buffer = cv2.imencode(".jpg", image_frame)
    return base64.b64encode(buffer).decode("utf-8")

def analyze_image_with_openai(base64_image):
    """Sends the image to OpenAI Vision API and returns the description."""
    try:
        response = client.chat.completions.create(
            model="gpt-4o", # Or use the latest vision model
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": OPENAI_PROMPT},
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{base64_image}"
                            },
                        },
                    ],
                }
            ],
            max_tokens=300,
        )
        if response.choices:
            return response.choices[0].message.content
        else:
            return "No description returned from API."
    except Exception as e:
        print(f"Error calling OpenAI API: {e}")
        return None

def analyze_image_with_yolo(image_frame):
    """Analyzes an image frame using YOLO and prints results."""
    print("Analyzing frame with YOLO...")
    try:
        results = yolo_model(image_frame)

        if results:
            print("\n--- YOLO Detection Results ---")
            # Display results (opens a window). Consider making this optional for library use.
            results[0].show()
            print("----------------------------\n")
            return results # Return the full results object
        else:
            print("No objects detected by YOLO.")
            return None
    except Exception as e:
        print(f"Error during YOLO analysis: {e}")
        return None

def check_for_person(results, model_names):
    """Checks YOLO results for the presence of a 'person' class."""
    if not results or not results[0].boxes:
        return False # No results or no boxes means no person

    for box in results[0].boxes:
        class_id = int(box.cls[0]) # Get class ID
        if model_names[class_id].lower() == 'person':
            print("Person detected by YOLO.")
            return True # Person found
    return False # No person found after checking all boxes

def process_image_for_analysis(image_frame):
    """
    Processes a single image frame (numpy array) with YOLO and conditionally with OpenAI.
    This is the primary function to call for analyzing an image.
    
    Returns the analysis result as a string.
    """
    if image_frame is None:
        print("Error: Input image_frame is None. Cannot process.")
        return "Error: Could not process the image."

    print(f"Processing image at {time.strftime('%Y-%m-%d %H:%M:%S')}")

    # --- YOLO Analysis ---
    yolo_results = analyze_image_with_yolo(image_frame)

    # --- Check for Person ---
    person_detected = False # Default to false
    if yolo_results:
        person_detected = check_for_person(yolo_results, yolo_model.names) # Check results for a person

    # --- OpenAI Analysis ---
    # Condition to send to OpenAI: EITHER the condition is disabled OR a person was detected
    if not OPENAI_CONDITION_PERSON or person_detected:
        # Encode image for OpenAI
        base64_image = encode_image_to_base64(image_frame)

        # Analyze image
        if OPENAI_CONDITION_PERSON:
            print("Sending image to OpenAI for analysis (person detected)...")
        else:
            print("Sending image to OpenAI for analysis (condition disabled)...")
        description = analyze_image_with_openai(base64_image)

        if description:
            print("\n--- OpenAI Analysis Result ---")
            print(description)
            print("-----------------------------\n")
            return description
        else:
            print("No description received from OpenAI.")
            return "No description could be generated for the image."
    elif OPENAI_CONDITION_PERSON: # Only print skip message if the condition is enabled but no person found
        print("Skipping OpenAI analysis: No person detected by YOLO.")
        return "No person detected in the image. Analysis skipped."