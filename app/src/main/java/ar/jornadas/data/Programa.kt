package ar.jornadas.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import retrofit2.http.GET
import kotlinx.coroutines.flow.Flow

data class FichaRemota(val id: String, val title: String, val track: String, val day: Int,
    val time: String, val room: String, val speaker: String, val summary: String)

interface ProgramaApi {
    @GET("programa") suspend fun consultar(): List<FichaRemota>
}

@Entity(tableName = "charlas")
data class Charla(@PrimaryKey val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String,
    val favorita: Boolean = false)

// Pedido 41: una fila acá significa "alguna vez llegó una edición válida", aunque viniera
// vacía (v4). Una lista vacía sola no alcanza para distinguir eso de "nunca descargué".
@Entity(tableName = "edicion")
data class Edicion(@PrimaryKey val id: Int = 0, val recibidaEn: Long)

@Dao
abstract class CarteleraDao {
    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    abstract fun observar(): Flow<List<Charla>>

    @Query("SELECT EXISTS(SELECT 1 FROM edicion)")
    abstract fun hayEdicion(): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun registrarEdicion(edicion: Edicion)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun guardar(fichas: List<Charla>)

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    abstract suspend fun alternar(id: String)

    @Query("SELECT id FROM charlas WHERE favorita = 1")
    protected abstract suspend fun idsFavoritas(): List<String>

    @Query("DELETE FROM charlas WHERE id NOT IN (:vigentes)")
    protected abstract suspend fun borrarRetiradas(vigentes: List<String>)

    @Query("DELETE FROM charlas")
    protected abstract suspend fun borrarTodo()

    // Pedido 42: la edición nueva reemplaza al programa completo, en una sola transacción.
    // - Las marcas se leen recién acá (cuando llegó la respuesta), así se respeta una marca
    //   puesta o sacada mientras la petición estaba en curso.
    // - Las charlas que ya no vienen se borran (con su marca): si regresan, regresan sin marcar.
    // - Las que siguen se actualizan (título, sala...) conservando su marca.
    // - Pedido 41: en la misma transacción se registra que llegó una edición válida.
    @Transaction
    open suspend fun reemplazarPrograma(nuevas: List<Charla>) {
        val favoritas = idsFavoritas().toSet()
        if (nuevas.isEmpty()) borrarTodo() else borrarRetiradas(nuevas.map { it.id })
        guardar(nuevas.map { it.copy(favorita = it.id in favoritas) })
        registrarEdicion(Edicion(recibidaEn = System.currentTimeMillis()))
    }
}

@Database(entities = [Charla::class, Edicion::class], version = 2, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao
}

// Migración no destructiva: los usuarios con la versión instalada conservan charlas y marcas.
// Solo se agrega la tabla nueva; si ya tenían charlas guardadas, eso cuenta como edición previa.
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `edicion` (`id` INTEGER NOT NULL, `recibidaEn` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT INTO edicion (id, recibidaEn) SELECT 0, 0 WHERE EXISTS (SELECT 1 FROM charlas)")
    }
}
