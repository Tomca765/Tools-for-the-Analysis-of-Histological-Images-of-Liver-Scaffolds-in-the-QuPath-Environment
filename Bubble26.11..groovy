import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.io.PrintWriter
import java.util.Base64
import java.awt.geom.PathIterator
import java.awt.Color
import javax.imageio.ImageIO
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.LineString
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import qupath.lib.roi.GeometryTools
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.gui.QuPathGUI
import com.google.gson.Gson

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.paint.Color as FxColor
import javafx.stage.Stage

String baseUrl     = "https://upstage-justice-oblong.ngrok-free.dev"
String analyzeUrl  = baseUrl + "/analyze"
String skeletonUrl = baseUrl + "/skeletonize"

// Fáze 1: Hrubý odhad z celého snímku
double COARSE_MAX_DIM = 1536.0

// Konstantní fyzické rozlišení podle kalibrace snímku (µm/px)
double BUBBLE_TARGET_UM_PER_PX  = 2.0     // Rozlišení pro zpřesnění obrysu buněk a centrálních žil
double LINES_TARGET_UM_PER_PX   = 1.0     // Vyšší rozlišení (1 µm/px) pro zachycení jemných světlých čar
double FALLBACK_FINE_DOWNSAMPLE = 2.0
double MAX_SAFE_CROP_DIM        = 5000.0
double CROP_PADDING             = 0.35

Label statusLabel = new Label("Status: Hrubě zakroužkuj žíly a klikni Run")
Button btnRun     = new Button("1. Inflate Bubbles")
Button btnLines   = new Button("2. Analyze Dark Lines")
Button btnClear   = new Button("Clear All")
Button btnCopyErr = new Button("Copy Error")
btnCopyErr.setDisable(true)

String lastErrorLog = ""

def recordError = { String context, Exception e ->
    def sw = new StringWriter()
    e.printStackTrace(new PrintWriter(sw))
    lastErrorLog = "=== QUPATH ERROR REPORT ===\n" +
                   "Action: " + context + "\n" +
                   "Base URL: " + baseUrl + "\n" +
                   "Message: " + e.getMessage() + "\n\n" +
                   "Stack Trace:\n" + sw.toString() + "\n" +
                   "==========================="
    println lastErrorLog
    Platform.runLater {
        statusLabel.setText("Status: ❌ Error (klikni na Copy Error)")
        btnCopyErr.setDisable(false)
        btnRun.setDisable(false)
        btnLines.setDisable(false)
    }
}

def gson = new Gson()
HttpClient httpClient = HttpClient.newHttpClient()

def computePhysicalDownsample = { server, double cropW, double cropH, double targetUmPerPx ->
    def cal = server.getPixelCalibration()
    double ds = FALLBACK_FINE_DOWNSAMPLE
    if (cal != null && cal.hasPixelSizeMicrons()) {
        double avgPixelUm = cal.getAveragedPixelSizeMicrons()
        if (avgPixelUm > 0) {
            ds = Math.max(1.0, targetUmPerPx / avgPixelUm)
        }
    }
    double minSafeDs = Math.max(cropW, cropH) / MAX_SAFE_CROP_DIM
    return Math.max(ds, minSafeDs)
}

def shapeToLocalPolys = { shape, double cropX, double cropY, double ds ->
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
    return subPolys
}

def extractPolysForRegion = { pathObjects, double cropX, double cropY, double cropW, double cropH, double ds, int targetIdx ->
    def polyList = []
    def objNames = []
    int localTargetIdx = -1

    pathObjects.eachWithIndex { obj, idx ->
        def roi = obj.getROI()
        boolean intersects = (roi.getBoundsX() <= cropX + cropW) &&
                             (roi.getBoundsX() + roi.getBoundsWidth() >= cropX) &&
                             (roi.getBoundsY() <= cropY + cropH) &&
                             (roi.getBoundsY() + roi.getBoundsHeight() >= cropY)

        if (idx == targetIdx || intersects) {
            def subPolys = shapeToLocalPolys(roi.getShape(), cropX, cropY, ds)
            if (!subPolys.isEmpty()) {
                def mainPoly = subPolys.max { it.size() }
                if (idx == targetIdx) {
                    localTargetIdx = polyList.size()
                }
                polyList.add(mainPoly)
                String sName = obj.getName() ? obj.getName().toString() : ("Vein " + (idx + 1))
                objNames.add(sName)
            }
        }
    }
    return [seeds: polyList, names: objNames, targetIdx: localTargetIdx]
}

def postJsonToColab = { String endpointUrl, bufferedImage, Map extraPayload ->
    def baos = new ByteArrayOutputStream()
    ImageIO.write(bufferedImage, "png", baos)
    String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray())

    def payload = [image: base64Image] + extraPayload

    HttpRequest httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(endpointUrl))
        .header("Content-Type", "application/json")
        .header("ngrok-skip-browser-warning", "true")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
        .build()

    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        String body = response.body() ?: "(prázdné tělo odpovědi)"
        if (body.length() > 1500) {
            body = body.substring(0, 1500) + "\n...[zkráceno]"
        }
        throw new RuntimeException("HTTP " + response.statusCode() + " na URL: " + endpointUrl + "\nBody:\n" + body)
    }
    return gson.fromJson(response.body(), Map.class)
}

// =========================================================================
// AKCE 1: NALEZENÍ PŘESNÉ CENTRÁLNÍ ŽÍLY + NAFUKOVÁNÍ BUBLIN
// =========================================================================
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
        btnLines.setDisable(true)
        btnCopyErr.setDisable(true)
        statusLabel.setText("Status: ⏳ Fáze 1/2: Hledám žíly a hrubý tvar (" + annotations.size() + ")...")
    }

    Thread.start {
        try {
            def server = getCurrentServer()
            double fullW = server.getWidth()
            double fullH = server.getHeight()

            double coarseDs = Math.max(2.0, Math.max(fullW, fullH) / COARSE_MAX_DIM)
            def coarseReq = RegionRequest.createInstance(server, coarseDs)
            def coarseImg = server.readBufferedImage(coarseReq)

            def coarseSeedData = extractPolysForRegion(annotations, 0.0, 0.0, fullW, fullH, coarseDs, -1)
            Map coarseResp = postJsonToColab(analyzeUrl, coarseImg, [
                seeds: coarseSeedData.seeds,
                seed_names: coarseSeedData.names
            ])

            List roughContours = (coarseResp.get("contours") ?: []) as List
            List roughVeins    = (coarseResp.get("vein_contours") ?: []) as List
            List roughPink     = coarseResp.get("pink_percentages") as List
            List roughNames    = coarseResp.get("seed_names") as List

            def hierarchy = imageData.getHierarchy()
            def oldAuto = hierarchy.getDetectionObjects().findAll {
                it.getPathClass()?.getName() in ["Vein Bubble", "Central Vein"]
            }
            if (!oldAuto.isEmpty()) {
                hierarchy.removeObjects(oldAuto, true)
            }

            def plane = ImagePlane.getDefaultPlane()
            def cellClass = PathClass.fromString("Vein Bubble", Color.RED.getRGB())
            def veinClass = PathClass.fromString("Central Vein", Color.YELLOW.getRGB())
            def newDetections = []
            int totalRough = roughContours.size()

            roughContours.eachWithIndex { rContour, idx ->
                Platform.runLater {
                    statusLabel.setText("Status: 🔍 Fáze 2/2: Zpřesňuji žílu a bublinu (" + (idx + 1) + "/" + totalRough + ")...")
                }

                double[] rx = rContour.collect { ((Number) it[0]).doubleValue() * coarseDs } as double[]
                double[] ry = rContour.collect { ((Number) it[1]).doubleValue() * coarseDs } as double[]

                double minX = rx.min()
                double maxX = rx.max()
                double minY = ry.min()
                double maxY = ry.max()

                if (idx < annotations.size()) {
                    def aRoi = annotations[idx].getROI()
                    minX = Math.min(minX, aRoi.getBoundsX())
                    maxX = Math.max(maxX, aRoi.getBoundsX() + aRoi.getBoundsWidth())
                    minY = Math.min(minY, aRoi.getBoundsY())
                    maxY = Math.max(maxY, aRoi.getBoundsY() + aRoi.getBoundsHeight())
                }

                double boxW = Math.max(100.0, maxX - minX)
                double boxH = Math.max(100.0, maxY - minY)
                double padLeft   = boxW * CROP_PADDING
                double padRight  = boxW * CROP_PADDING
                double padTop    = boxH * CROP_PADDING
                double padBottom = boxH * CROP_PADDING

                double[] finalX = rx
                double[] finalY = ry
                double[] veinX = null
                double[] veinY = null

                if (idx < roughVeins.size() && roughVeins[idx] != null && roughVeins[idx].size() >= 3) {
                    veinX = roughVeins[idx].collect { ((Number) it[0]).doubleValue() * coarseDs } as double[]
                    veinY = roughVeins[idx].collect { ((Number) it[1]).doubleValue() * coarseDs } as double[]
                }

                double pinkPct = (roughPink != null && idx < roughPink.size() && roughPink[idx] != null) ? ((Number) roughPink[idx]).doubleValue() : 0.0
                String vName = (roughNames != null && idx < roughNames.size() && roughNames[idx]) ? roughNames[idx].toString() : ("Vein " + (idx + 1))

                // Automaticky zvětší výřez (až 3 pokusy), pokud se bublina dotkne okraje výřezu
                for (int attempt = 0; attempt < 3; attempt++) {
                    int cropX  = (int) Math.max(0, Math.floor(minX - padLeft))
                    int cropY  = (int) Math.max(0, Math.floor(minY - padTop))
                    int cropX2 = (int) Math.min(fullW, Math.ceil(maxX + padRight))
                    int cropY2 = (int) Math.min(fullH, Math.ceil(maxY + padBottom))
                    int cropW  = Math.max(1, cropX2 - cropX)
                    int cropH  = Math.max(1, cropY2 - cropY)

                    double fineDs = computePhysicalDownsample(server, cropW, cropH, BUBBLE_TARGET_UM_PER_PX)
                    def fineReq = RegionRequest.createInstance(server.getPath(), fineDs, cropX, cropY, cropW, cropH, 0, 0)
                    def fineImg = server.readBufferedImage(fineReq)

                    def fineSeedData = extractPolysForRegion(annotations, cropX, cropY, cropW, cropH, fineDs, idx)
                    if (fineSeedData.targetIdx < 0) break

                    Map fineResp = postJsonToColab(analyzeUrl, fineImg, [
                        seeds: fineSeedData.seeds,
                        seed_names: fineSeedData.names,
                        target_index: fineSeedData.targetIdx
                    ])
                    List fContours = (fineResp.get("contours") ?: []) as List
                    List fVeins    = (fineResp.get("vein_contours") ?: []) as List
                    List fPink     = (fineResp.get("pink_percentages") ?: []) as List

                    if (!fVeins.isEmpty() && fVeins[0] != null && fVeins[0].size() >= 3) {
                        veinX = fVeins[0].collect { (((Number) it[0]).doubleValue() * fineDs) + cropX } as double[]
                        veinY = fVeins[0].collect { (((Number) it[1]).doubleValue() * fineDs) + cropY } as double[]
                    }

                    if (!fContours.isEmpty() && fContours[0].size() >= 3) {
                        finalX = fContours[0].collect { (((Number) it[0]).doubleValue() * fineDs) + cropX } as double[]
                        finalY = fContours[0].collect { (((Number) it[1]).doubleValue() * fineDs) + cropY } as double[]
                        if (fPink != null && !fPink.isEmpty() && fPink[0] != null) {
                            pinkPct = ((Number) fPink[0]).doubleValue()
                        }

                        double margin = fineDs * 4.0
                        boolean hitLeft   = (cropX > 0) && (finalX.min() <= cropX + margin)
                        boolean hitRight  = (cropX2 < fullW) && (finalX.max() >= cropX2 - margin)
                        boolean hitTop    = (cropY > 0) && (finalY.min() <= cropY + margin)
                        boolean hitBottom = (cropY2 < fullH) && (finalY.max() >= cropY2 - margin)

                        if (!(hitLeft || hitRight || hitTop || hitBottom)) {
                            break
                        }
                        if (hitLeft)   padLeft   += boxW * 0.75
                        if (hitRight)  padRight  += boxW * 0.75
                        if (hitTop)    padTop    += boxH * 0.75
                        if (hitBottom) padBottom += boxH * 0.75
                    } else {
                        break
                    }
                }

                if (veinX != null && veinX.length >= 3) {
                    def vRoi = ROIs.createPolygonROI(veinX, veinY, plane)
                    def veinObj = PathObjects.createDetectionObject(vRoi, veinClass)
                    veinObj.setName("Central Vein (" + vName + ")")
                    newDetections.add(veinObj)
                }

                if (finalX.length >= 3) {
                    def roi = ROIs.createPolygonROI(finalX, finalY, plane)
                    def cellObject = PathObjects.createDetectionObject(roi, cellClass)
                    cellObject.getMeasurements().put("Pink %", pinkPct)
                    cellObject.setName(String.format("%s | Pink: %.1f%%", vName, pinkPct))
                    newDetections.add(cellObject)
                }
            }

            hierarchy.addObjects(newDetections)
            fireHierarchyUpdate()

            int bubbleCount = newDetections.count { it.getPathClass()?.getName() == "Vein Bubble" }
            Platform.runLater {
                statusLabel.setText(String.format("Status: ✅ Hotovo! (%d žil a bublin)", bubbleCount))
                btnRun.setDisable(false)
                btnLines.setDisable(false)
            }
        } catch (Exception e) {
            recordError("1. Inflate Bubbles", e)
        }
    }
}

// =========================================================================
// AKCE 2: SKELETONIZACE ŽIL V OKOLÍ CENTRÁLNÍ ŽÍLY
// =========================================================================
def runDarkLinesAnalysis = {
    def imageData = getCurrentImageData()
    if (imageData == null) {
        Platform.runLater { statusLabel.setText("Status: Error - No image open!") }
        return
    }

    def hierarchy = imageData.getHierarchy()
    def cells = hierarchy.getDetectionObjects().findAll { obj ->
        if (obj.getROI() == null) return false
        String clsName = obj.getPathClass()?.getName()
        String objName = obj.getName() ?: ""
        return clsName == "Vein Bubble" || objName.contains("Pink:")
    }

    if (cells.isEmpty()) {
        Platform.runLater { statusLabel.setText("Status: ⚠️ Nejdřív vytvoř buňky (krok 1)!") }
        return
    }

    def oldSkeletons = hierarchy.getDetectionObjects().findAll {
        it.getPathClass()?.getName() == "Dark Line Skeleton"
    }
    if (!oldSkeletons.isEmpty()) {
        hierarchy.removeObjects(oldSkeletons, true)
    }

    Platform.runLater {
        btnRun.setDisable(true)
        btnLines.setDisable(true)
        btnCopyErr.setDisable(true)
        statusLabel.setText("Status: 🧬 Hledám žíly v okolí (" + cells.size() + " buněk)...")
    }

    Thread.start {
        try {
            def server = getCurrentServer()
            double fullW = server.getWidth()
            double fullH = server.getHeight()

            def cal = server.getPixelCalibration()
            boolean hasMicrons = (cal != null && cal.hasPixelSizeMicrons())
            double avgPixelUm = hasMicrons ? cal.getAveragedPixelSizeMicrons() : 1.0
            String lenUnit = hasMicrons ? "µm" : "px"
            String areaUnit = hasMicrons ? "µm²" : "px²"

            def exactVeins = hierarchy.getDetectionObjects().findAll {
                it.getROI() != null && it.getPathClass()?.getName() == "Central Vein"
            }
            boolean hasExactVeins = !exactVeins.isEmpty()
            def excludeObjects = hasExactVeins ? exactVeins : hierarchy.getAnnotationObjects().findAll { it.getROI() != null }

            def plane = ImagePlane.getDefaultPlane()
            def geomFactory = GeometryTools.getDefaultFactory()
            def skelClass = PathClass.fromString("Dark Line Skeleton", Color.GREEN.getRGB())
            def newSkeletonObjects = []
            int processedCount = 0

            for (int idx = 0; idx < cells.size(); idx++) {
                def cell = cells[idx]
                int currentNum = idx + 1

                Platform.runLater {
                    statusLabel.setText("Status: 🧬 Žíly a skeleton (" + currentNum + "/" + cells.size() + ")...")
                }

                def roi = cell.getROI()
                int cropX  = (int) Math.max(0, Math.floor(roi.getBoundsX()))
                int cropY  = (int) Math.max(0, Math.floor(roi.getBoundsY()))
                int cropX2 = (int) Math.min(fullW, Math.ceil(roi.getBoundsX() + roi.getBoundsWidth()))
                int cropY2 = (int) Math.min(fullH, Math.ceil(roi.getBoundsY() + roi.getBoundsHeight()))
                int cropW  = Math.max(1, cropX2 - cropX)
                int cropH  = Math.max(1, cropY2 - cropY)

                double fineDs = computePhysicalDownsample(server, cropW, cropH, LINES_TARGET_UM_PER_PX)
                def fineReq = RegionRequest.createInstance(server.getPath(), fineDs, cropX, cropY, cropW, cropH, 0, 0)
                def fineImg = server.readBufferedImage(fineReq)
                if (fineImg == null) continue

                def cellPolys = shapeToLocalPolys(roi.getShape(), cropX, cropY, fineDs)
                if (cellPolys.isEmpty()) continue
                def mainCellPoly = cellPolys.max { it.size() }

                def excludeData = extractPolysForRegion(excludeObjects, cropX, cropY, cropW, cropH, fineDs, -1)

                Map skelResp = postJsonToColab(skeletonUrl, fineImg, [
                    cell_polygon: mainCellPoly,
                    exclude_seeds: excludeData.seeds,
                    is_exact_vein: hasExactVeins
                ])

                double lengthScaledPx = ((Number) (skelResp.get("length_px") ?: 0.0)).doubleValue()
                double areaScaledPx   = ((Number) (skelResp.get("area_px") ?: 0.0)).doubleValue()

                double realLength = lengthScaledPx * fineDs * avgPixelUm
                double realArea   = areaScaledPx * (fineDs * fineDs) * (avgPixelUm * avgPixelUm)
                double densityMm  = (realArea > 0 && hasMicrons) ? (realLength / (realArea / 1_000_000.0)) : 0.0

                String areaKey = "Cell Area [" + areaUnit + "]"
                String lenKey  = "Line Length [" + lenUnit + "]"
                cell.getMeasurements().put(areaKey, realArea)
                cell.getMeasurements().put(lenKey, realLength)
                if (realArea > 0 && hasMicrons) {
                    cell.getMeasurements().put("Line Density [µm/mm²]", densityMm)
                }

                String rawName = cell.getName() ? cell.getName().toString() : ("Vein " + currentNum)
                String baseName = rawName.split(" \\| Len:")[0]
                cell.setName(String.format("%s | Len: %.1f %s | Area: %.0f %s", baseName, realLength, lenUnit, realArea, areaUnit))

                List skelContours = (skelResp.get("skeleton_contours") ?: []) as List
                List<LineString> lineStrings = []

                for (def cnt : skelContours) {
                    if (cnt != null && cnt.size() >= 2) {
                        Coordinate[] coords = new Coordinate[cnt.size()]
                        for (int i = 0; i < cnt.size(); i++) {
                            def pt = cnt[i]
                            double px = (((Number) pt[0]).doubleValue() * fineDs) + cropX
                            double py = (((Number) pt[1]).doubleValue() * fineDs) + cropY
                            coords[i] = new Coordinate(px, py)
                        }
                        lineStrings.add(geomFactory.createLineString(coords))
                    }
                }

                if (!lineStrings.isEmpty()) {
                    def multiLine = geomFactory.createMultiLineString(lineStrings as LineString[])
                    def multiRoi = GeometryTools.geometryToROI(multiLine, plane)
                    def skelObj = PathObjects.createDetectionObject(multiRoi, skelClass)

                    String skelLenKey  = "Total Skeleton Length [" + lenUnit + "]"
                    String skelAreaKey = "Parent Cell Area [" + areaUnit + "]"
                    skelObj.getMeasurements().put(skelLenKey, realLength)
                    skelObj.getMeasurements().put(skelAreaKey, realArea)
                    if (realArea > 0 && hasMicrons) {
                        skelObj.getMeasurements().put("Line Density [µm/mm²]", densityMm)
                    }

                    skelObj.setName(String.format("Skeleton (%s) | Len: %.1f %s", baseName, realLength, lenUnit))
                    newSkeletonObjects.add(skelObj)
                }
                processedCount++
            }

            if (!newSkeletonObjects.isEmpty()) {
                hierarchy.addObjects(newSkeletonObjects)
            }
            fireHierarchyUpdate()

            Platform.runLater {
                statusLabel.setText(String.format("Status: ✅ Změřeno %d buněk (%d souhrnných skeletonů)", processedCount, newSkeletonObjects.size()))
                btnRun.setDisable(false)
                btnLines.setDisable(false)
            }
        } catch (Exception e) {
            recordError("2. Analyze Dark Lines", e)
        }
    }
}

def clearColabObjects = {
    def imageData = getCurrentImageData()
    if (imageData == null) return

    def hierarchy = imageData.getHierarchy()
    def colabObjects = hierarchy.getFlattenedObjectList(null).findAll {
        it.isDetection() && (
            it.getPathClass()?.getName() in ["Vein Bubble", "Central Vein", "Dark Line Skeleton"] ||
            (it.getName() != null && (it.getName().contains("Pink:") || it.getName().startsWith("Vein ")))
        )
    }

    if (!colabObjects.isEmpty()) {
        hierarchy.removeObjects(colabObjects, true)
        fireHierarchyUpdate()
        statusLabel.setText("Status: Cleared " + colabObjects.size() + " objects")
    }
}

// =========================================================================
// GUI UI (ADAPTIVNÍ KONTRAST + COPY ERROR)
// =========================================================================
Platform.runLater {
    Stage stage = new Stage()
    stage.setTitle("Vein Bubble & Skeleton Analyzer")
    stage.setAlwaysOnTop(true)

    btnRun.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;")
    btnRun.setOnAction { runColabAnalysis() }

    btnLines.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;")
    btnLines.setOnAction { runDarkLinesAnalysis() }

    btnClear.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;")
    btnClear.setOnAction { clearColabObjects() }

    btnCopyErr.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white; -fx-font-weight: bold;")
    btnCopyErr.setOnAction {
        if (lastErrorLog) {
            def content = new ClipboardContent()
            content.putString(lastErrorLog)
            Clipboard.getSystemClipboard().setContent(content)
            statusLabel.setText("Status: 📋 Chyba zkopírována do schránky!")
        }
    }

    statusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -fx-text-background-color;")

    HBox buttonBox = new HBox(8, btnRun, btnLines, btnClear, btnCopyErr)
    buttonBox.setAlignment(Pos.CENTER)

    VBox layout = new VBox(12, statusLabel, buttonBox)
    layout.setPadding(new Insets(15))
    layout.setAlignment(Pos.CENTER)

    Scene scene = new Scene(layout, 520, 105)

    try {
        def mainStage = QuPathGUI.getInstance()?.getStage()
        if (mainStage != null) {
            stage.initOwner(mainStage)
            if (mainStage.getScene() != null) {
                scene.getStylesheets().addAll(mainStage.getScene().getStylesheets())
            }
        }
    } catch (Exception ignored) {}

    def updateLabelContrast = {
        FxColor bgColor = null
        def fills = layout.getBackground()?.getFills()
        if (fills != null && !fills.isEmpty() && fills.last().getFill() instanceof FxColor) {
            bgColor = (FxColor) fills.last().getFill()
        } else if (scene.getFill() instanceof FxColor) {
            bgColor = (FxColor) scene.getFill()
        }

        if (bgColor != null) {
            double luminance = 0.299 * bgColor.getRed() + 0.587 * bgColor.getGreen() + 0.114 * bgColor.getBlue()
            String textHex = (luminance < 0.5) ? "#F5F5F5" : "#1A1A1A"
            statusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + textHex + ";")
        } else {
            statusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -fx-text-background-color;")
        }
    }

    layout.backgroundProperty().addListener({ obs, oldBg, newBg -> updateLabelContrast() } as javafx.beans.value.ChangeListener)

    stage.setScene(scene)
    stage.show()
    Platform.runLater { updateLabelContrast() }
}