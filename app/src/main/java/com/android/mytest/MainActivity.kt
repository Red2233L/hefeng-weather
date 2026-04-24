package com.android.mytest

import android.content.Context
import androidx.compose.foundation.layout.statusBarsPadding
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random


data class CityLookupResponse(
    val code: String,
    val location: List<CityLocation>
)
data class CityLocation(
    val id: String,
    val name: String,
    val adm1: String = "",
    val adm2: String = "",
    val country: String = ""
)

data class WeatherNowResponse(
    val code: String,
    val now: WeatherNow
)
data class WeatherNow(
    val temp: String,
    @SerializedName("text") val weatherText: String,
    val windDir: String,
    val windScale: String,
    val humidity: String,
    val icon: String = "100"
)

data class WeatherDailyResponse(
    val code: String,
    val daily: List<DailyWeather>
)
data class DailyWeather(
    val fxDate: String,
    val tempMax: String,
    val tempMin: String,
    @SerializedName("textDay") val weatherTextDay: String,
    val iconDay: String = "100"
)

data class Weather(
    val cityName: String,
    val now: WeatherNow,
    val dailyList: List<DailyWeather>
)


interface QWeatherApi {
    @GET("geo/v2/city/lookup")
    suspend fun searchCity(@Query("location") city: String): CityLookupResponse

    @GET("v7/weather/now")
    suspend fun getNow(@Query("location") cityId: String): WeatherNowResponse

    @GET("v7/weather/3d")
    suspend fun getDaily(@Query("location") cityId: String): WeatherDailyResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://k538m26f3w.re.qweatherapi.com/"
    private const val API_KEY = "a5db256899d84f5ab8d4179568911471"

    val api: QWeatherApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val url = original.url.newBuilder()
                    .addQueryParameter("key", API_KEY)
                    .build()
                chain.proceed(original.newBuilder().url(url).build())
            }
            .build()

        val gson = GsonBuilder()
            .setLenient()
            .create()

        val retrofit: Retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        retrofit.create(QWeatherApi::class.java)
    }
}


val Context.dataStore by preferencesDataStore("weather_prefs")

data class SavedCity(val id: String, val name: String)

class CityStorage(private val context: Context) {
    private val CITY_ID = stringPreferencesKey("city_id")
    private val CITY_NAME = stringPreferencesKey("city_name")

    suspend fun save(city: SavedCity) {
        context.dataStore.edit { prefs ->
            prefs[CITY_ID] = city.id
            prefs[CITY_NAME] = city.name
        }
    }

    suspend fun getSaved(): SavedCity? {
        val prefs = context.dataStore.data.first()
        val id = prefs[CITY_ID] ?: return null
        val name = prefs[CITY_NAME] ?: return null
        return SavedCity(id, name)
    }
}


class WeatherRepository {
    private val api = RetrofitClient.api

    suspend fun searchCity(city: String): Result<List<CityLocation>> =
        runCatching {
            val resp = api.searchCity(city)
            if (resp.code == "200") resp.location
            else throw Exception("搜索失败，错误码: ${resp.code}")
        }

    suspend fun getWeather(cityId: String): Result<Weather> =
        runCatching {
            val nowResp = api.getNow(cityId)
            val dailyResp = api.getDaily(cityId)
            if (nowResp.code == "200" && dailyResp.code == "200") {
                Weather("", nowResp.now, dailyResp.daily)
            } else {
                throw Exception("获取天气失败，now:${nowResp.code} daily:${dailyResp.code}")
            }
        }
}


class WeatherViewModel : ViewModel() {
    private val repository = WeatherRepository()

    private val _searchResults = MutableStateFlow<List<CityLocation>>(emptyList())
    val searchResults: StateFlow<List<CityLocation>> = _searchResults.asStateFlow()

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var currentCity: SavedCity? = null

    fun searchCity(city: String) {
        viewModelScope.launch {
            _loading.value = true
            repository.searchCity(city).onSuccess {
                _searchResults.value = it
            }.onFailure {
                _error.value = it.message
                _searchResults.value = emptyList()
            }
            _loading.value = false
        }
    }

    fun fetchWeather(cityId: String, cityName: String) {
        viewModelScope.launch {
            _loading.value = true
            repository.getWeather(cityId).onSuccess { w ->
                _weather.value = w.copy(cityName = cityName)
            }.onFailure {
                _error.value = it.message
            }
            _loading.value = false
        }
        currentCity = SavedCity(cityId, cityName)
    }

    fun clearError() {
        _error.value = null
    }
}


object WeatherColorGenerator {
    private val colorMap = mutableMapOf<String, Pair<Color, Color>>()

    fun getColors(iconCode: String): Pair<Color, Color> {
        return colorMap.getOrPut(iconCode) {
            val seed = iconCode.toIntOrNull() ?: 0
            val rng = Random(seed)
            val primary = Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            val secondary = Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            primary to secondary
        }
    }
}


@Composable
fun WeatherNowCard(now: WeatherNow) {
    val (iconColor, secondaryColor) = WeatherColorGenerator.getColors(now.icon)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 圆形代替图片
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = shortWeatherText(now.weatherText),
                    color = Color.White,
                    fontSize = 26.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("${now.temp}℃", fontSize = 48.sp, color = iconColor)
            Text(now.weatherText, fontSize = 18.sp)

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DetailItem("湿度", "${now.humidity}%", secondaryColor)
                DetailItem("风向", now.windDir, secondaryColor)
                DetailItem("风力", "${now.windScale}级", secondaryColor)
            }
        }
    }
}

fun shortWeatherText(text: String): String = when {
    text.contains("晴") -> "晴"
    text.contains("云") -> "多云"
    text.contains("雨") -> "雨"
    text.contains("雪") -> "雪"
    text.contains("风") -> "风"
    text.contains("雾") -> "雾"
    else -> text.take(2)
}

@Composable
fun DetailItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyLarge, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DailyForecast(dailyList: List<DailyWeather>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("未来天气预报", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            dailyList.forEach { day ->
                val (iconColor, _) = WeatherColorGenerator.getColors(day.iconDay)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 颜色条代替图标
                    Box(
                        modifier = Modifier
                            .size(8.dp, 24.dp)
                            .background(iconColor, shape = RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = formatDateShort(day.fxDate),
                        modifier = Modifier.width(60.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = day.weatherTextDay,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${day.tempMin}~${day.tempMax}℃",
                        color = iconColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

fun formatDateShort(dateStr: String): String {
    return try {
        val input = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val date = input.parse(dateStr)
        val output = SimpleDateFormat("MM/dd", Locale.getDefault())
        date?.let { output.format(it) } ?: dateStr
    } catch (e: Exception) {
        dateStr
    }
}


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF4FC3F7),
                    secondary = Color(0xFF03A9F4),
                    background = Color(0xFFF5F5F5),
                    surface = Color.White,
                )
            ) {
                val vm: WeatherViewModel = viewModel()
                val cityStorage = remember { CityStorage(applicationContext) }

                LaunchedEffect(Unit) {
                    cityStorage.getSaved()?.let {
                        vm.fetchWeather(it.id, it.name)
                    }
                }

                MainScreen(vm, cityStorage)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: WeatherViewModel, cityStorage: CityStorage) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.95f))
                    .statusBarsPadding()
            ) {
                PlaceSearchDrawer(vm) { city ->
                    vm.fetchWeather(city.id, city.name)
                    scope.launch {
                        try {
                            cityStorage.save(SavedCity(city.id, city.name))
                        } catch (e: Exception) {
                            Log.e("MainScreen", "保存城市失败", e)
                        }
                        drawerState.close()
                    }
                }
            }
        }
    )  {
        WeatherScreenContent(vm) {
            scope.launch { drawerState.open() }
        }
    }
}

@Composable
fun PlaceSearchDrawer(vm: WeatherViewModel, onCitySelected: (CityLocation) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by vm.searchResults.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("搜索城市", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("城市名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.searchCity(query) }, modifier = Modifier.fillMaxWidth()) {
            Text("搜索")
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (error != null) {
            Text("错误: $error", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }
        LazyColumn {
            items(results.size) { index ->
                val city = results[index]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onCitySelected(city) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(city.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${city.adm1} ${city.adm2}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherScreenContent(vm: WeatherViewModel, onMenuClick: () -> Unit) {
    val weather by vm.weather.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (weather != null) {
            val (bgColor, _) = WeatherColorGenerator.getColors(weather!!.now.icon)


            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor.copy(alpha = 0.08f))
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 头部：菜单按钮 + 城市名 + 刷新按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.Filled.Menu, contentDescription = "切换城市")
                        }
                        Text(
                            text = weather!!.cityName,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            vm.currentCity?.let { vm.fetchWeather(it.id, it.name) }
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新天气")
                        }
                    }

                    WeatherNowCard(weather!!.now)
                    Spacer(Modifier.height(16.dp))
                    DailyForecast(weather!!.dailyList)
                    Spacer(Modifier.height(32.dp))
                }
            }
        } else {

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("点击左上角菜单搜索城市")
                    if (error != null) {
                        Text("错误: $error", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }


        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}