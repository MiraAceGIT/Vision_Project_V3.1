package com.blind_helmet.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import java.util.List;

/**
 * AnnotatedImageView — ImageView avec boîtes de détection
 * 
 * EN GROS : Un ImageView custom qui dessine les boîtes de détection YOLO
 * par-dessus l'image. Affiche la classe et la confiance de chaque objet.
 * 
 * CE QUE ÇA FAIT :
 * - Affiche l'image normale (ImageView standard)
 * - Dessine des rectangles colorés autour des objets détectés
 * - Couleur selon confiance :
 *   * Vert : >80% (très sûr)
 *   * Jaune : 60-80% (moyen)
 *   * Rouge : <60% (peu sûr)
 * - Affiche label + confiance au-dessus de chaque boîte
 * - Ajuste les coordonnées depuis l'espace image vers l'espace vue
 * 
 * USAGE : MainActivity appelle setDetections() pour mettre à jour l'affichage
 */
public class AnnotatedImageView extends ImageView {
    
    private DetectionData detectionData;
    private Paint boxPaint;
    private Paint textPaint;
    private Paint labelBackgroundPaint;
    private boolean showAnnotations = true;
    
    public AnnotatedImageView(Context context) {
        super(context);
        initializePaints();
    }
    
    public AnnotatedImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initializePaints();
    }
    
    public AnnotatedImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializePaints();
    }
    
    private void initializePaints() {
        // Paint pour les boîtes
        boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStrokeWidth(3.0f);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setAntiAlias(true);
        
        // Paint pour le texte
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32f);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setAntiAlias(true);
        
        // Paint pour le fond du label
        labelBackgroundPaint = new Paint();
        labelBackgroundPaint.setColor(Color.argb(200, 0, 128, 0));  // Semi-transparent green
        labelBackgroundPaint.setStyle(Paint.Style.FILL);
        labelBackgroundPaint.setAntiAlias(true);
    }
    
    public void setDetections(DetectionData detectionData) {
        this.detectionData = detectionData;
        invalidate();  // Redessiner
    }
    
    public void setShowAnnotations(boolean show) {
        this.showAnnotations = show;
        invalidate();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (!showAnnotations || detectionData == null || detectionData.detections.isEmpty()) {
            return;
        }
        
        // Récupérer les dimensions de l'image
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        
        int imageWidth = drawable.getIntrinsicWidth();
        int imageHeight = drawable.getIntrinsicHeight();
        
        // Récupérer les dimensions du view
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        
        // Calculer le scale factor (dépend du scaleType)
        float scaleX = (float) viewWidth / imageWidth;
        float scaleY = (float) viewHeight / imageHeight;
        
        // Dessiner chaque boîte
        for (DetectionData.Detection det : detectionData.detections) {
            // Convertir les coordonnées image → view
            float x1 = det.x1 * scaleX;
            float y1 = det.y1 * scaleY;
            float x2 = det.x2 * scaleX;
            float y2 = det.y2 * scaleY;
            
            // Adapter la couleur selon la confiance
            float confidence = det.confidence;
            if (confidence > 0.8f) {
                boxPaint.setColor(Color.GREEN);
                labelBackgroundPaint.setColor(Color.argb(200, 0, 128, 0));
            } else if (confidence > 0.6f) {
                boxPaint.setColor(Color.YELLOW);
                labelBackgroundPaint.setColor(Color.argb(200, 128, 128, 0));
            } else {
                boxPaint.setColor(Color.RED);
                labelBackgroundPaint.setColor(Color.argb(200, 128, 0, 0));
            }
            
            // Dessiner la boîte
            canvas.drawRect(x1, y1, x2, y2, boxPaint);
            
            // Préparer le label
            String label = String.format("%s\n%.1f%%", det.className, confidence * 100);
            
            // Calculer la taille du texte
            Rect textBounds = new Rect();
            textPaint.getTextBounds(label, 0, label.length(), textBounds);
            
            // Dessiner le fond du label
            float labelPadding = 8f;
            float labelX1 = x1;
            float labelY1 = Math.max(0, y1 - textBounds.height() - labelPadding * 2);
            float labelX2 = x1 + textBounds.width() + labelPadding * 2;
            float labelY2 = labelY1 + textBounds.height() + labelPadding * 2;
            
            canvas.drawRect(labelX1, labelY1, labelX2, labelY2, labelBackgroundPaint);
            
            // Dessiner le texte
            canvas.drawText(
                    label,
                    labelX1 + labelPadding,
                    labelY1 + textBounds.height() + labelPadding,
                    textPaint
            );
        }
    }
}
