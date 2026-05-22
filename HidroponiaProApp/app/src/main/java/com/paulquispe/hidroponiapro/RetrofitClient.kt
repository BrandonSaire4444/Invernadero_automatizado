package com.paulquispe.hidroponiapro

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * RetrofitClient gestiona la comunicación con la API REST.
 * Se implementa como Singleton (object) para eficiencia en el uso de recursos.
 */
object RetrofitClient {
    // IMPORTANTE: Asegúrate de que esta URL sea accesible desde el emulador/dispositivo.
    // Si usas el emulador, "10.0.2.2" suele apuntar a localhost de tu PC.
    private const val BASE_URL = "http://127.0.0.1:8000/"

    // Reutilizamos el cliente base para evitar crear múltiples conexiones innecesarias
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Devuelve una instancia de ApiService configurada con el token JWT si está disponible.
     */
    fun getApiService(token: String? = null): ApiService {
        // Creamos un nuevo builder basado en el cliente original si necesitamos inyectar el token
        val client = if (!token.isNullOrEmpty()) {
            baseClient.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(request)
                }
                .build()
        } else {
            baseClient
        }

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

/*
    NOTAS DE CONFIGURACIÓN OBLIGATORIAS:

    1. AndroidManifest.xml:
       - Permiso de red: <uses-permission android:name="android.permission.INTERNET" />
       - Tráfico HTTP (si tu servidor no tiene HTTPS):
         Añade dentro de la etiqueta <application>: android:usesCleartextTraffic="true"

    2. Dependencias (build.gradle):
       - Asegúrate de tener:
         implementation 'com.squareup.retrofit2:retrofit:2.9.0'
         implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
         implementation 'com.squareup.okhttp3:okhttp:4.10.0'

    3. Estructura de Arquitectura:

       Este flujo asegura que cada solicitud pase por el interceptor de autorización antes
       de ser enviada al servidor, manteniendo la seguridad y eficiencia.
*/