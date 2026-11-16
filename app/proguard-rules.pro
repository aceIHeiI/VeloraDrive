# El servicio de notificaciones lo instancia el sistema por nombre.
-keep class com.iheii.veloradrive.MediaListenerService { <init>(); }

# Quita los logs de depuración en release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
