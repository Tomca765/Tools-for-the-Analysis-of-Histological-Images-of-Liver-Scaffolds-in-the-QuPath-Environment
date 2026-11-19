import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import com.google.gson.Gson

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.stage.Stage

String colabUrl = "https://upstage-justice-oblong.ngrok-free.dev/analyze"
double downsample = 4.0

Label statusLabel = new Label("Status: Ready")
Button btnRun = new Button("Detect Large Cells")
Button btnClear = new Button("Clear Detections")

def runColabAnalysis = {
    def imageData = getCurrentImageData()
    if (imageData == null) {
        Platform.runLater { statusLabel.setText("Status: Error - No image open!") }
        return
    }

    Platform.runLater {
        btnRun.setDisable(true)
        statusLabel.setText("Status: ⏳ Analyzing image...")
    }

    Thread.start {
        try {
            def server = getCurrentServer()
            def selected = getSelectedObject()

            def request
            double offsetX = 0
            double offsetY = 0

            if (selected != null && selected.getROI() != null) {
                def roi = selected.getROI()
                offsetX = roi.getBoundsX()
                offsetY = roi.getBoundsY()
                request = RegionRequest.createInstance(server.getPath(), downsample, roi)
            } else {
                request = RegionRequest.createInstance(server, downsample)
            }

            def bufferedImage = server.readBufferedImage(request)
            def baos = new ByteArrayOutputStream()
            ImageIO.write(bufferedImage, "png", baos)
            byte[] imageBytes = baos.toByteArray()

            HttpClient client = HttpClient.newHttpClient()
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(colabUrl))
                .header("Content-Type", "image/png")
                .header("ngrok-skip-browser-warning", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                .build()

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                def gson = new Gson()
                Map responseMap = gson.fromJson(response.body(), Map.class)

                List contours = (responseMap.get("contours") ?: []) as List
                List pinkPercentages = responseMap.get("pink_percentages") as List

                def plane = ImagePlane.getDefaultPlane()
                def newDetections = []

                contours.eachWithIndex { contour, idx ->
                    double[] xCoords = contour.collect { (((Number) it[0]).doubleValue() * downsample) + offsetX } as double[]
                    double[] yCoords = contour.collect { (((Number) it[1]).doubleValue() * downsample) + offsetY } as double[]

                    if (xCoords.length >= 3) {
                        def roi = ROIs.createPolygonROI(xCoords, yCoords, plane)
                        def cellObject = PathObjects.createDetectionObject(roi)
                        
                        double pinkPct = 0.0
                        if (pinkPercentages != null && idx < pinkPercentages.size() && pinkPercentages[idx] != null) {
                            pinkPct = ((Number) pinkPercentages[idx]).doubleValue()
                        }
                        
                        // Zápis měření přes getMeasurements().put()
                        cellObject.getMeasurements().put("Pink %", pinkPct)
                        cellObject.setName(String.format("Pink: %.1f%%", pinkPct))
                        
                        newDetections.add(cellObject)
                    }
                }

                addObjects(newDetections)
                fireHierarchyUpdate()

                Platform.runLater {
                    statusLabel.setText("Status: ✅ Done! (${newDetections.size()} cells)")
                    btnRun.setDisable(false)
                }
            } else {
                Platform.runLater {
                    statusLabel.setText("Status: ❌ Server Error (${response.statusCode()})")
                    btnRun.setDisable(false)
                }
            }
        } catch (Exception e) {
            println "Error details: " + e
            Platform.runLater {
                statusLabel.setText("Status: ❌ Error: " + e.getMessage())
                btnRun.setDisable(false)
            }
        }
    }
}

def clearColabObjects = {
    def imageData = getCurrentImageData()
    if (imageData == null) return

    def hierarchy = imageData.getHierarchy()
    def colabObjects = hierarchy.getFlattenedObjectList(null).findAll { 
        it.getName() != null && (it.getName().startsWith("Pink:") || it.getName() == "Colab Cell")
    }

    if (!colabObjects.isEmpty()) {
        hierarchy.removeObjects(colabObjects, true)
        fireHierarchyUpdate()
        statusLabel.setText("Status: Cleared ${colabObjects.size()} objects")
    }
}

// GUI UI
Platform.runLater {
    Stage stage = new Stage()
    stage.setTitle("Colab Large Cell Segmenter")
    stage.setAlwaysOnTop(true)

    btnRun.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;")
    btnRun.setOnAction { runColabAnalysis() }

    btnClear.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;")
    btnClear.setOnAction { clearColabObjects() }

    statusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #333333;")

    HBox buttonBox = new HBox(10, btnRun, btnClear)
    buttonBox.setAlignment(Pos.CENTER)

    VBox layout = new VBox(12, statusLabel, buttonBox)
    layout.setPadding(new Insets(15))
    layout.setAlignment(Pos.CENTER)

    stage.setScene(new Scene(layout, 340, 100))
    stage.show()
}