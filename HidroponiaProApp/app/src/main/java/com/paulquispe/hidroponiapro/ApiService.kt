package com.paulquispe.hidroponiapro

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

// =============================================================================
// INTERFAZ DE RED (APISERVICE)
// =============================================================================
interface ApiService {

    @POST("api/usuarios/registrar")
    suspend fun registrarUsuario(@Body usuario: UsuarioRegistro): Response<Map<String, String>>

    @POST("api/cultivos/registrar")
    suspend fun registrarCultivo(@Body cultivo: CultivoRegistro): Response<Map<String, String>>

    @GET("api/cultivos")
    suspend fun listarCultivos(): Response<ListaCultivosResponse>

    @PUT("api/cultivos/{id_cultivo}")
    suspend fun editarCultivo(
        @Path("id_cultivo") idCultivo: String,
        @Body datos: CultivoEdicion
    ): Response<Map<String, String>>

    @DELETE("api/cultivos/{id_cultivo}")
    suspend fun eliminarCultivoLogico(
        @Path("id_cultivo") idCultivo: String
    ): Response<Map<String, String>>
}

// =============================================================================
// MODELOS DE DATOS UNIFICADOS
// =============================================================================

data class UsuarioRegistro(
    @SerializedName("nombre") val nombre: String,
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class CultivoRegistro(
    @SerializedName("id_cultivo") val id_cultivo: String? = null, // ¡Añade esta línea!
    @SerializedName("nombre_verdura") val nombre_verdura: String,
    @SerializedName("temp_min") val temp_min: Float,
    @SerializedName("temp_max") val temp_max: Float,
    @SerializedName("hum_min") val hum_min: Float,
    @SerializedName("hum_max") val hum_max: Float,
    @SerializedName("ph_min") val ph_min: Float,
    @SerializedName("ph_max") val ph_max: Float,
    @SerializedName("luz_min") val luz_min: Float,
    @SerializedName("luz_max") val luz_max: Float
)

data class CultivoEdicion(
    @SerializedName("nombre_verdura") val nombre_verdura: String,
    @SerializedName("temp_min") val temp_min: Float,
    @SerializedName("temp_max") val temp_max: Float,
    @SerializedName("hum_min") val hum_min: Float,
    @SerializedName("hum_max") val hum_max: Float,
    @SerializedName("ph_min") val ph_min: Float,
    @SerializedName("ph_max") val ph_max: Float,
    @SerializedName("luz_min") val luz_min: Float,
    @SerializedName("luz_max") val luz_max: Float
)

data class CultivoRespuesta(
    @SerializedName("id_cultivo") val id_cultivo: String,
    @SerializedName("nombre_verdura") val nombre_verdura: String
) {
    override fun toString(): String = nombre_verdura
}

data class ListaCultivosResponse(
    @SerializedName("cultivos") val cultivos: List<CultivoRespuesta>
)