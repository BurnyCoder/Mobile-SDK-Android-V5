# AI Image Analysis Feature

This feature enables the DJI drone camera to take a photo, send it to OpenAI for analysis, and speak the result using text-to-speech.

## Setup

1. **API Key Configuration**
   - Open `/SampleCode-V5/android-sdk-v5-sample/src/main/python/.env`
   - Add your OpenAI API key: `OPENAI_API_KEY=your_api_key_here`
   - Customize the prompt if desired: `OPENAI_PROMPT="What is in this image? Describe it briefly but thoroughly."`

2. **Build Configuration**
   - The project uses Chaquopy to integrate Python with Kotlin
   - Required Python packages (installed automatically during build):
     - openai
     - opencv-python
     - python-dotenv
     - ultralytics (for YOLO object detection)

## How It Works

1. The "Analyze with AI" button in the Media fragment captures a photo using the drone camera
2. The photo is downloaded to the device
3. Python code analyzes the image:
   - YOLO object detection identifies objects in the image
   - OpenAI Vision API provides detailed analysis
4. The analysis result is displayed as a toast message
5. Text-to-speech reads the analysis result

## Customizing Analysis

### Change the Analysis Prompt

To change what the AI responds with:
- Edit the `OPENAI_PROMPT` in the `.env` file

### Person Detection Filter

This feature includes an option to only send images to OpenAI when a person is detected in the image:
- Set `OPENAI_CONDITION_PERSON=true` in the `.env` file to enable this filter
- Set `OPENAI_CONDITION_PERSON=false` to analyze all images regardless of content

## Technical Implementation

- **MediaVM.kt**: Contains methods for image capture, TTS, and OpenAI integration
- **ai_processing.py**: Python code for image analysis, YOLO detection, and OpenAI API calls
- **PythonBridge.kt**: Kotlin bridge to Python code using Chaquopy

## Troubleshooting

- If you get "Python environment initialization failed" errors, ensure Chaquopy is properly configured in build.gradle
- If you get OpenAI API errors, check your API key and internet connection
- If TTS doesn't work, ensure text-to-speech is enabled on the device