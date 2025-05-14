YOLO Object Detection App

Overview

The YOLO Object Detection App is an Android application designed to assist visually impaired users by detecting doors and obstacles in real-time using the device's camera. It leverages three TensorFlow Lite models:





YOLO for door detection.



YOLOv8 for obstacle detection.



MiDaS for depth estimation.

The app provides audio feedback through Text-to-Speech (TTS) to guide users with navigation instructions, such as directing them toward a door or alerting them to obstacles. The application uses Jetpack Compose for the UI and CameraX for camera functionality, ensuring a modern and efficient user experience.

Features





Real-Time Detection: Detects doors and obstacles using the device's rear camera.



Depth Estimation: Estimates the distance to obstacles using the MiDaS model.



Audio Guidance: Provides navigation instructions via TTS, such as "Go left towards the door" or "Stop! Obstacle detected."



Continuous Scanning: Supports continuous scanning mode for real-time updates every 3 seconds.



Camera Permission Handling: Requests and manages camera permissions gracefully.



Efficient Processing: Utilizes NNAPI for accelerated model inference on compatible devices.

Prerequisites





Android Device: Android 7.0 (API level 24) or higher.



Development Environment:





Android Studio (latest stable version recommended).



Gradle 7.0 or higher.



Dependencies:





TensorFlow Lite with NNAPI support.



CameraX (version 1.3.0 or higher).



Jetpack Compose (version 1.5.0 or higher).



Kotlin Coroutines.



Model Files:





YOLO model for door detection (door_model.tflite).



YOLOv8 model for obstacle detection (obstacle_model.tflite).



MiDaS model for depth estimation (depth_model.tflite).

Setup Instructions

1. Clone the Repository

git clone https://github.com/your-repo/yolo-object-detection.git
cd yolo-object-detection

2. Add Model Files

Place the following TensorFlow Lite model files in the app/src/main/assets directory:





door_model.tflite



obstacle_model.tflite



depth_model.tflite



Note: Ensure the model paths match the constants defined in Constants.kt (e.g., DOOR_MODEL_PATH, OBSTACLE_MODEL_PATH, DEPTH_MODEL_PATH).

3. Configure Build

Open the project in Android Studio and ensure the following dependencies are included in app/build.gradle:

dependencies {
    implementation "org.tensorflow:tensorflow-lite:2.14.0"
    implementation "org.tensorflow:tensorflow-lite-support:0.4.4"
    implementation "org.tensorflow:tensorflow-lite-delegate-nnapi:2.14.0"
    implementation "androidx.camera:camera-core:1.3.0"
    implementation "androidx.camera:camera-camera2:1.3.0"
    implementation "androidx.camera:camera-lifecycle:1.3.0"
    implementation "androidx.camera:camera-view:1.3.0"
    implementation "androidx.compose.ui:ui:1.5.0"
    implementation "androidx.compose.material3:material3:1.2.0"
    implementation "androidx.activity:activity-compose:1.8.0"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
}

Sync the project with Gradle to resolve dependencies.

4. Build and Run





Connect an Android device or start an emulator.



Build and run the app using Android Studio.

Usage





Grant Camera Permission: On first launch, the app requests camera permission. Grant it to enable detection.



Camera Preview: The app displays a camera preview using the rear camera.



Capture Mode:





Press the Capture button to take a photo and analyze it for doors and obstacles.



The app processes the image and provides audio feedback with navigation guidance.



Continuous Scanning Mode:





Press the Start button to enable continuous scanning.



The app captures and analyzes images every 3 seconds, providing real-time audio updates.



Press Stop to disable continuous scanning.



Navigation Guidance:





The app uses TTS to guide users, e.g., "Go straight towards the door" or "Stop! Obstacle detected at approximately 2.5 meters."



Guidance is based on door positions, obstacle locations, and estimated depths.

Technical Details





Architecture:





The app follows a modular design with Jetpack Compose for UI and CameraX for camera integration.



TensorFlow Lite models are loaded from assets and run with NNAPI for hardware acceleration.



Model Processing:





YOLO: Detects doors with a confidence threshold of 0.8.



YOLOv8: Detects obstacles (80 classes, e.g., person, chair) with a confidence threshold of 0.6.



MiDaS: Generates depth maps for obstacles to estimate proximity.



Non-Maximum Suppression (NMS) is applied to filter overlapping detections (IoU threshold: 0.8).



Image Processing:





Images are preprocessed to 640x640 for YOLO/YOLOv8 and 256x256 for MiDaS.



Outputs are post-processed to scale bounding boxes to the original image dimensions.



Navigation Logic:





The app evaluates door and obstacle positions to determine if the path is clear.



Depth estimates help prioritize close obstacles (threshold: 1.5 units).



Uniform image detection prevents guidance when the camera is too close to an object.

Limitations





Model Accuracy: Detection accuracy depends on the quality of the TensorFlow Lite models and lighting conditions.



Device Performance: Real-time processing may be slower on low-end devices without NNAPI support.



Depth Estimation: MiDaS provides relative depth, not absolute distances, which may affect guidance precision.



Camera Orientation: The app assumes the device is held in portrait mode for accurate bounding box scaling.

Contributing

Contributions are welcome! To contribute:





Fork the repository.



Create a feature branch (git checkout -b feature/your-feature).



Commit your changes (git commit -m "Add your feature").



Push to the branch (git push origin feature/your-feature).



Open a Pull Request.

Please ensure code follows the project's coding standards and includes relevant tests.

License

This project is licensed under the MIT License. See the LICENSE file for details.

Acknowledgments





TensorFlow Lite for model inference.



CameraX and Jetpack Compose for modern Android development.



YOLO and MiDaS communities for advancing object detection and depth estimation.

Contact

For questions or support, please open an issue on the GitHub repository or contact your-email@example.com.
