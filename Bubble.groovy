import java.io.ByteArrayOutputStream
import java.util.Base64
import java.awt.geom.PathIterator
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

// Fáze 1: Hrubý odhad z celého snímku
double COARSE_MAX_DIM = 1536.0
// Fáze 2: Ostrý výřez pro každou bublinu zvlášť (zvětšený o CROP_PADDING na každou stranu)
double FINE_MAX_DIM   = 2048.0
double CROP_PADDING   = 0.25   // +25 % okraj kolem hrubého tvaru

Label statusLabel = new Label("Status: Zakroužkuj žíly a klikni Run")
Button btnRun = new Button("Inflate Vein Bubbles")
Button btnClear = new Button("Clear Bubbles")

def gson = new Gson()
HttpClient httpClient = HttpClient.newHttpClient()

// Pomocná funkce: převede anotace v daném výřezu na lokální polygony
def extractSeedsForRegion = { annotations, double cropX, double cropY, double cropW, double cropH, double ds, int targetAnnIdx ->
    def seedsList = []
    def seedNames = []
    int localTargetIdx = -1

    annotations.eachWithIndex { ann, idx ->
        def roi = ann.getROI()
        // Zahrneme cílovou žílu i všechny sousední žíly, které zasahují do výřezu
        boolean intersects = (roi.getBoundsX() <= cropX + cropW) &&
                             (roi.getBoundsX() + roi.getBoundsWidth() >= cropX) &&
                             (roi.getBoundsY() <= cropY + cropH) &&
                             (roi.getBoundsY() + roi.getBoundsHeight() >= cropY)

        if (idx == targetAnnIdx || intersects) {
            def shape = roi.getShape()
            def iter = shape.getPathIterator(null, ds)
            double[] coords = new double[6]
            def subPolys = []
            def currentPoly = []

            while (!iter.isDone()) {
                int type = iter.currentSegment(coords)
                if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                    int px = (int) Math.round((coords[0] - cropX) / ds)
                    int py = (int) Math.round((coords[1] - cropY) / ds)
                    currentPoly.add([px, py])
                } else if (type == PathIterator.SEG_CLOSE) {
                    if (currentPoly.size() >= 3) {
                        subPolys.add(currentPoly)
                    }
                    currentPoly = []
                }
                iter.next()
            }
            if (currentPoly.size() >= 3) {
                subPolys.add(currentPoly)
            }

            if (!subPolys.isEmpty()) {
                def mainPoly = subPolys.max { it.size() }
                if (idx == targetAnnIdx) {
                    localTargetIdx = seedsList.size()
                }
                seedsList.add(mainPoly)
                seedNames.add(ann.getName() ?: "Vein ${idx + 1}")
            }
        }
    }
    return [seeds: seedsList, names: seedNames, targetIdx: localTargetIdx]
}

// Pomocná funkce: odešle výřez na Colab server
def sendToColab = { bufferedImage, seedsList, seedNames, Integer targetIndex ->
    def baos = new ByteArrayOutputStream()
    ImageIO.write(bufferedImage, "png", baos)
    String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray())

    def payload = [
        image: base64Image,
        seeds: seedsList,
        seed_names: seedNames
    ]
    if (targetIndex != null) {
        payload.target_index = targetIndex
    }

    HttpRequest httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(colabUrl))
        .header("Content-Type", "application/json")
        .header("ngrok-skip-browser-warning", "true")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
        .build()

    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        throw new RuntimeException("Server error HTTP ${response.statusCode()}")
    }
    return gson.fromJson(response.body(), Map.class)
}

def runColabAnalysis = {
    def imageData = getCurrentImageData()
    if (imageData == null) {
        Platform.runLater { statusLabel.setText("Status: Error - No image open!") }
        return
    }

    def annotations = getAnnotationObjects().findAll { it.getROI() != null }
    if (annotations.isEmpty()) {
        Platform.runLater { statusLabel.setText("Status: ⚠️ Nejdřív zakroužkuj centrální žíly!") }
        return
    }

    Platform.runLater {
        btnRun.setDisable(true)
        statusLabel.setText("Status: ⏳ Fáze 1/2: Hledám hrubý tvar (${annotations.size()} žil)...")
    }

    Thread.start {
        try {
            def server = getCurrentServer()
            double fullW = server.getWidth()
            double fullH = server.getHeight()

            // =================================================================
            // FÁZE 1: HRUBÁ SEGMENTACE NA CELÉM ZMENŠENÉM SNÍMKU
            // =================================================================
            double coarseDs = Math.max(2.0, Math.max(fullW, fullH) / COARSE_MAX_DIM)
            def coarseReq = RegionRequest.createInstance(server, coarseDs)
            def coarseImg = server.readBufferedImage(coarseReq)

            def coarseSeedData = extractSeedsForRegion(annotations, 0.0, 0.0, fullW, fullH, coarseDs, -1)
            Map coarseResp = sendToColab(coarseImg, coarseSeedData.seeds, coarseSeedData.names, null)

            List roughContours = (coarseResp.get("contours") ?: []) as List
            List roughPink = coarseResp.get("pink_percentages") as List
            List roughNames = coarseResp.get("seed_names") as List

            def plane = ImagePlane.getDefaultPlane()
            def newDetections = []
            int totalRough = roughContours.size()

            // =================================================================
            // FÁZE 2: VYSOKÉ ROZLIŠENÍ NA ZVĚTŠENÉM VÝŘEZU KAŽDÉ BUBLINY
            // =================================================================
            roughContours.eachWithIndex { rContour, idx ->
                Platform.runLater {
                    statusLabel.setText("Status: 🔍 Fáze 2/2: Zpřesňuji detail (${idx + 1}/${totalRough})...")
                }

                // Souřadnice hrubé bubliny v plném rozlišení snímku
                double[] rx = rContour.collect { ((Number) it[0]).doubleValue() * coarseDs } as double[]
                double[] ry = rContour.collect { ((Number) it[1]).doubleValue() * coarseDs } as double[]

                double minX = rx.min()
                double maxX = rx.max()
                double minY = ry.min()
                double maxY = ry.max()

                // Pojistka: zahrnout i samotnou zakroužkovanou anotaci
                if (idx < annotations.size()) {
                    def aRoi = annotations[idx].getROI()
                    minX = Math.min(minX, aRoi.getBoundsX())
                    maxX = Math.max(maxX, aRoi.getBoundsX() + aRoi.getBoundsWidth())
                    minY = Math.min(minY, aRoi.getBoundsY())
                    maxY = Math.max(maxY, aRoi.getBoundsY() + aRoi.getBoundsHeight())
                }

                // Rozšíření výřezu o CROP_PADDING (+25 % na každou stranu)
                double boxW = Math.max(100.0, maxX - minX)
                double boxH = Math.max(100.0, maxY - minY)
                double padX = boxW * CROP_PADDING
                double padY = boxH * CROP_PADDING

                int cropX = (int) Math.max(0, Math.floor(minX - padX))
                int cropY = (int) Math.max(0, Math.floor(minY - padY))
                int cropX2 = (int) Math.min(fullW, Math.ceil(maxX + padX))
                int cropY2 = (int) Math.min(fullH, Math.ceil(maxY + padY))
                int cropW = Math.max(1, cropX2 - cropX)
                int cropH = Math.max(1, cropY2 - cropY)

                double fineDs = Math.max(1.0, Math.max(cropW, cropH) / FINE_MAX_DIM)
                def fineReq = RegionRequest.createInstance(server.getPath(), fineDs, cropX, cropY, cropW, cropH, 0, 0)
                def fineImg = server.readBufferedImage(fineReq)

                def fineSeedData = extractSeedsForRegion(annotations, cropX, cropY, cropW, cropH, fineDs, idx)

                double[] finalX = rx
                double[] finalY = ry
                double pinkPct = (roughPink != null && idx < roughPink.size() && roughPink[idx] != null) ? ((Number) roughPink[idx]).doubleValue() : 0.0
                String vName = (roughNames != null && idx < roughNames.size() && roughNames[idx]) ? roughNames[idx] : "Vein ${idx + 1}"

                if (fineSeedData.targetIdx >= 0) {
                    Map fineResp = sendToColab(fineImg, fineSeedData.seeds, fineSeedData.names, fineSeedData.targetIdx)
                    List fContours = (fineResp.get("contours") ?: []) as List
                    List fPink = fineResp.get("pink_percentages") as List

                    if (!fContours.isEmpty() && fContours[0].size() >= 3) {
                        finalX = fContours[0].collect { (((Number) it[0]).doubleValue() * fineDs) + cropX } as double[]
                        finalY = fContours[0].collect { (((Number) it[1]).doubleValue() * fineDs) + cropY } as double[]
                        if (fPink != null && !fPink.isEmpty() && fPink[0] != null) {
                            pinkPct = ((Number) fPink[0]).doubleValue()
                        }
                    }
                }

                if (finalX.length >= 3) {
                    def roi = ROIs.createPolygonROI(finalX, finalY, plane)
                    def cellObject = PathObjects.createDetectionObject(roi)
                    cellObject.getMeasurements().put("Pink %", pinkPct)
                    cellObject.setName(String.format("%s | Pink: %.1f%%", vName, pinkPct))
                    newDetections.add(cellObject)
                }
            }

            addObjects(newDetections)
            fireHierarchyUpdate()

            Platform.runLater {
                statusLabel.setText("Status: ✅ Hotovo! (${newDetections.size()} zpřesněných buněk)")
                btnRun.setDisable(false)
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
        it.isDetection() && it.getName() != null && (it.getName().contains("Pink:") || it.getName().startsWith("Vein "))
    }

    if (!colabObjects.isEmpty()) {
        hierarchy.removeObjects(colabObjects, true)
        fireHierarchyUpdate()
        statusLabel.setText("Status: Cleared ${colabObjects.size()} bubbles")
    }
}

// GUI UI
Platform.runLater {
    Stage stage = new Stage()
    stage.setTitle("Vein Bubble Segmenter (2-Pass HD)")
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

    stage.setScene(new Scene(layout, 380, 105))
    stage.show()
}