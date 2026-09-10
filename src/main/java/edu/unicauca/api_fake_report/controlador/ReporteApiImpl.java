package edu.unicauca.api_fake_report.controlador;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sivri.report.api.ReportesEstadisticosApi;
import com.sivri.report.model.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class ReporteApiImpl implements ReportesEstadisticosApi {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public ResponseEntity<ApiResponseReportes> obtenerTiposDisponibles() {
        try {
            InputStream inputStream = new ClassPathResource("reportes.json").getInputStream();

            List<ReporteMetadata> reportes = mapper.readValue(
                    inputStream,
                    new TypeReference<List<ReporteMetadata>>() {
                    });

            ApiResponseReportes response = new ApiResponseReportes();
            response.setStatus(200);
            response.setUserMessage("Ok");
            response.setDeveloperMessage("");
            response.setData(reportes);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Override
    public ResponseEntity<ApiResponseReportePaginado> listarDatosReporte(
            String codigo,
            Integer pageNo,
            Integer pageSize,
            FiltrosReporteRequest filtrosReporteRequest) {

        int page = pageNo != null ? pageNo : 0;
        int size = pageSize != null ? pageSize : 10;

        List<Map<String, Object>> dataCompleta = obtenerDataQuemada(codigo);

        log.info("Listando datos para reporte '{}' con filtros: {}", codigo, filtrosReporteRequest);

        ReporteMetadata metadata = obtenerMetadataPorCodigo(codigo);

        List<Map<String, Object>> dataFiltrada = aplicarFiltros(dataCompleta, filtrosReporteRequest, metadata);

        int total = dataFiltrada.size();

        int start = Math.min(page * size, total);
        int end = Math.min(start + size, total);

        List<Map<String, Object>> content = dataFiltrada.subList(start, end);

        Pageable pageable = new Pageable();
        pageable.setPageNumber(page);
        pageable.setPageSize(size);
        pageable.setOffset(start);
        pageable.setPaged(true);
        pageable.setUnpaged(false);

        PageReporteData pageData = new PageReporteData();
        pageData.setContent(content);
        pageData.setPageable(pageable);
        pageData.setTotalElements(total);
        pageData.setTotalPages((int) Math.ceil((double) total / size));
        pageData.setNumber(page);
        pageData.setSize(size);
        pageData.setFirst(page == 0);
        pageData.setLast(end >= total);
        pageData.setNumberOfElements(content.size());
        pageData.setEmpty(content.isEmpty());

        ApiResponseReportePaginado response = new ApiResponseReportePaginado();
        response.setStatus(200);
        response.setUserMessage("Ok");
        response.setDeveloperMessage("");
        response.setData(pageData);

        return ResponseEntity.ok(response);
    }

//    @Override
//    public ResponseEntity<Resource> generarReporte(
//            String codigo,
//            String formato,
//            FiltrosReporteRequest request) { // Renombrado a 'request' por legibilidad
//
//        try {
//
//            List<Map<String, Object>> dataFuente;
//
//            // 1. DETERMINAR LA FUENTE DE DATOS:
//            // Si el request contiene 'data', esa es nuestra fuente. Si no, usamos la data quemada.
//            if (request != null && request.getData() != null && !request.getData().isEmpty()) {
//                dataFuente = request.getData();
//            } else {
//                dataFuente = obtenerDataQuemada(codigo);
//            }
//
//            ReporteMetadata metadata = obtenerMetadataPorCodigo(codigo);
//
//            // 2. APLICAR FILTROS A LA FUENTE DE DATOS:
//            List<Map<String, Object>> dataFinal = aplicarFiltros(dataFuente, request, metadata);
//
//            // 3. PREPARAR PETICIÓN AL MICROSERVICIO:
//            ReporteExternoRequest requestExterno = new ReporteExternoRequest();
//            requestExterno.setTipoReporte(codigo);
//            requestExterno.setFormato(formato);
//            requestExterno.setData(dataFinal); // Pasamos la data final (obtenida del body y filtrada)
//
//            HttpHeaders headers = new HttpHeaders();
//            headers.setContentType(MediaType.APPLICATION_JSON);
//
//            log.info("REQUEST: {}", requestExterno);
//
//            HttpEntity<ReporteExternoRequest> entity = new HttpEntity<>(requestExterno, headers);
//
//            // 4. EJECUTAR REST TEMPLATE:
//            ResponseEntity<byte[]> responseExterno = restTemplate.exchange(
//                    "http://localhost:8081/reportes/generar",
//                    HttpMethod.POST,
//                    entity,
//                    byte[].class);
//
//            byte[] archivo = responseExterno.getBody();
//
//            if (archivo == null || archivo.length == 0) {
//                return ResponseEntity.internalServerError().build();
//            }
//
//            MediaType contentType = responseExterno.getHeaders().getContentType();
//
//            if (contentType == null) {
//                contentType = "pdf".equalsIgnoreCase(formato)
//                        ? MediaType.APPLICATION_PDF
//                        : MediaType.parseMediaType(
//                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
//            }
//
//            String extension = "pdf".equalsIgnoreCase(formato) ? "pdf" : "xlsx";
//
//            return ResponseEntity.ok()
//                    .contentType(contentType)
//                    .contentLength(archivo.length)
//                    .header(
//                            HttpHeaders.CONTENT_DISPOSITION,
//                            "attachment; filename=\"reporte-" + codigo.toLowerCase() + "." + extension + "\"")
//                    .body(new ByteArrayResource(archivo));
//
//        } catch (Exception e) {
//            log.error("Error generando reporte: ", e);
//            return ResponseEntity.internalServerError().build();
//        }
//    }

    @Override
    public ResponseEntity<Resource> generarReporte(
            String codigo,
            String formato,
            FiltrosReporteRequest request) {

        try {
            List<Map<String, Object>> dataFuente;

            if (request != null && request.getData() != null && !request.getData().isEmpty()) {
                dataFuente = request.getData();
            } else {
                dataFuente = obtenerDataQuemada(codigo);
            }

            ReporteMetadata metadata = obtenerMetadataPorCodigo(codigo);
            List<Map<String, Object>> dataFinal = aplicarFiltros(dataFuente, request, metadata);

            // =========================================================
            // NUEVO: INTERCEPCIÓN DE CONSULTAS ADICIONALES (CERTIFICADOS)
            // =========================================================
            Map<String, Object> parametrosJasperAdicionales = new HashMap<>();

            if ("pdf".equalsIgnoreCase(formato) && metadata != null
                    && metadata.getConsultasAdicionales() != null
                    && metadata.getConsultasAdicionales().getCertificado() != null) {

                CertificadoConfig certConfig = metadata.getConsultasAdicionales().getCertificado();

                if ("PDF".equalsIgnoreCase(certConfig.getFormato()) && certConfig.getConsultas() != null) {

                    Map<String, Object> filaCertificado = dataFinal.isEmpty() ? new HashMap<>() : dataFinal.get(0);

                    for (ConsultaConfig consulta : certConfig.getConsultas()) {
                        String valorFiltro = String.valueOf(filaCertificado.get(consulta.getCampoFiltro()));

                        // Ahora este método irá al archivo JSON y traerá la lista filtrada
                        List<Map<String, Object>> subData = simularConsultaAdicional(consulta.getTipoConsulta(), valorFiltro);

                        parametrosJasperAdicionales.put(consulta.getLlaveJasper(), subData);
                    }
                }
            }
            // =========================================================

            ReporteExternoRequest requestExterno = new ReporteExternoRequest();
            requestExterno.setTipoReporte(codigo);
            requestExterno.setFormato(formato);
            requestExterno.setData(dataFinal);

            // DEBES ASEGURARTE de agregar este mapa a tu modelo ReporteExternoRequest
            // requestExterno.setParametrosAdicionales(parametrosJasperAdicionales);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ReporteExternoRequest> entity = new HttpEntity<>(requestExterno, headers);

            ResponseEntity<byte[]> responseExterno = restTemplate.exchange(
                    "http://localhost:8081/reportes/generar",
                    HttpMethod.POST,
                    entity,
                    byte[].class);

            byte[] archivo = responseExterno.getBody();
            if (archivo == null || archivo.length == 0) {
                return ResponseEntity.internalServerError().build();
            }

            MediaType contentType = responseExterno.getHeaders().getContentType();
            if (contentType == null) {
                contentType = "pdf".equalsIgnoreCase(formato)
                        ? MediaType.APPLICATION_PDF
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }

            String extension = "pdf".equalsIgnoreCase(formato) ? "pdf" : "xlsx";

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .contentLength(archivo.length)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"reporte-" + codigo.toLowerCase() + "." + extension + "\"")
                    .body(new ByteArrayResource(archivo));

        } catch (Exception e) {
            log.error("Error generando reporte: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Override
    public ResponseEntity<List<Map<String, Object>>> obtenerDataAdicional(String tipoConsulta, String valorFiltro) {

        List<Map<String, Object>> subData = new ArrayList<>();

        try {
            // Enrutador Agnóstico de Consultas
            switch (tipoConsulta.toUpperCase()) {

                case "INTEGRANTES_POR_GRUPO":
                    // Lee el archivo json de integrantes y filtra por el ID enviado
                    try (InputStream inputStream = new ClassPathResource("datos-reporte-r02.json").getInputStream()) {
                        List<Map<String, Object>> todos = mapper.readValue(
                                inputStream,
                                new TypeReference<List<Map<String, Object>>>() {});

                        subData = todos.stream()
                                .filter(i -> valorFiltro.equals(String.valueOf(i.get("idGrupo"))))
                                .collect(Collectors.toList());
                    }
                    break;

                case "DETALLE_INTEGRANTE_PROYECTO":
                    log.info("Buscando detalle anidado del investigador con ID: {}", valorFiltro);
                    try (InputStream inputStream = new ClassPathResource("detalle-integrantes-proyectos.json").getInputStream()) {
                        List<Map<String, Object>> todos = mapper.readValue(
                                inputStream,
                                new TypeReference<List<Map<String, Object>>>() {});

                        // Busca a la persona por su número de identificación
                        subData = todos.stream()
                                .filter(i -> valorFiltro.equals(String.valueOf(i.get("numeroIdentificacion"))))
                                .collect(Collectors.toList());
                    }
                    break;

                default:
                    log.warn("Tipo de consulta adicional no soportado: {}", tipoConsulta);
                    return ResponseEntity.badRequest().build();
            }

            return ResponseEntity.ok(subData);

        } catch (Exception e) {
            log.error("Error obteniendo data adicional para {}: ", tipoConsulta, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // =========================================================
    // SIMULADOR DE FÁBRICA DE PROVEEDORES (Para tu API Fake)
    // =========================================================
    private List<Map<String, Object>> simularConsultaAdicional(String tipoConsulta, String idFiltro) {
        List<Map<String, Object>> subData = new ArrayList<>();

        if ("INTEGRANTES_POR_GRUPO".equals(tipoConsulta)) {
            log.info("Buscando integrantes en integrantes.json para el Grupo ID: {}", idFiltro);

            try (InputStream inputStream = new ClassPathResource("datos-reporte-r02.json").getInputStream()) {
                // 1. Leer todo el archivo
                List<Map<String, Object>> todosLosIntegrantes = mapper.readValue(
                        inputStream,
                        new TypeReference<List<Map<String, Object>>>() {
                        });

                // 2. Filtrar únicamente los que pertenecen al grupo solicitado
                subData = todosLosIntegrantes.stream()
                        .filter(integrante -> idFiltro.equals(String.valueOf(integrante.get("idGrupo"))))
                        .collect(Collectors.toList());

                log.info("Se encontraron {} integrantes para el grupo {}", subData.size(), idFiltro);

            } catch (Exception e) {
                log.error("Error leyendo el archivo integrantes.json: ", e);
            }
        }

        // Si a futuro agregas "ROLES_POR_PROYECTO", agregas otro bloque if aquí leyendo otro json

        return subData;
    }

    private ReporteMetadata obtenerMetadataPorCodigo(String codigo) {
        // Llama al método que ya lee el reportes.json
        ApiResponseReportes catalogos = obtenerTiposDisponibles().getBody();

        if (catalogos != null && catalogos.getData() != null) {
            return catalogos.getData().stream()
                    .filter(r -> r.getCodigo().equalsIgnoreCase(codigo))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    @Override
    public ResponseEntity<ApiResponseEstadisticas> obtenerEstadisticasReporte(
            String codigo,
            FiltrosReporteRequest filtrosReporteRequest) {

        try {
            // 1. Obtener definición del reporte consultando el propio servicio
            ApiResponseReportes resCatalogos = obtenerTiposDisponibles().getBody();
            if (resCatalogos == null || resCatalogos.getData() == null) {
                return ResponseEntity.internalServerError().build();
            }

            // Buscar la metadata del reporte actual
            ReporteMetadata metadata = resCatalogos.getData().stream()
                    .filter(r -> r.getCodigo().equalsIgnoreCase(codigo))
                    .findFirst()
                    .orElse(null);

            if (metadata == null || metadata.getEstadisticas() == null || metadata.getEstadisticas().isEmpty()) {
                // No hay estadísticas configuradas, devolver vacío
                ApiResponseEstadisticas response = new ApiResponseEstadisticas();
                response.setStatus(200);
                response.setUserMessage("Sin estadísticas");
                response.setData(new HashMap<>());
                return ResponseEntity.ok(response);
            }

//            ReporteMetadata metadata2 = obtenerMetadataPorCodigo(codigo);

            // 2. Obtener y filtrar datos (como en la tabla)
            List<Map<String, Object>> dataCompleta = obtenerDataQuemada(codigo);
            List<Map<String, Object>> dataFiltrada = aplicarFiltros(dataCompleta, filtrosReporteRequest, metadata);

            Map<String, Object> estadisticasResult = new HashMap<>();

            // 3. MOTOR AGNÓSTICO: Iterar la configuración y calcular
            for (ConfiguracionEstadisticas config : metadata.getEstadisticas()) {

                Integer limiteCategorias = config.getMaxCategorias();

                // NUEVO: Omitir gráfico si el usuario aplicó un filtro restrictivo
//                if (config.getOcultarConFiltros() != null && !config.getOcultarConFiltros().isEmpty()) {
//                    Map<String, Object> filtrosActuales = filtrosReporteRequest.getFiltros();
//                    boolean debeOcultar = config.getOcultarConFiltros().stream()
//                            .anyMatch(filtro -> filtrosActuales != null &&
//                                    filtrosActuales.containsKey(filtro) &&
//                                    filtrosActuales.get(filtro) != null &&
//                                    !filtrosActuales.get(filtro).toString().isBlank());
//                    if (debeOcultar) {
//                        continue; // Salta al siguiente gráfico, este no se envía al front
//                    }
//                }

                if (limiteCategorias != null && config.getIgnorarLimiteConFiltros() != null && !config.getIgnorarLimiteConFiltros().isEmpty()) {
                    Map<String, Object> filtrosActuales = filtrosReporteRequest.getFiltros();
                    boolean debeIgnorarLimite = config.getIgnorarLimiteConFiltros().stream()
                            .anyMatch(filtro -> filtrosActuales != null &&
                                    filtrosActuales.containsKey(filtro) &&
                                    filtrosActuales.get(filtro) != null &&
                                    !filtrosActuales.get(filtro).toString().isBlank());
                    if (debeIgnorarLimite) {
                        limiteCategorias = null; // Apaga el límite, permite que pasen todos los registros
                    }
                }

                // Aplicar pre-filtro si el gráfico lo exige (Ej. solo contar ACTIVOS)
                List<Map<String, Object>> dataAGraficar = dataFiltrada;
                if (config.getFiltroFijoClave() != null && config.getFiltroFijoValor() != null) {
                    dataAGraficar = dataFiltrada.stream()
                            .filter(m -> config.getFiltroFijoValor().equalsIgnoreCase(String.valueOf(m.get(config.getFiltroFijoClave()))))
                            .toList();
                }

                // Enrutar al cálculo matemático según el TIPO DE GRÁFICO
                switch (config.getTipoGrafico()) {
                    case ConfiguracionEstadisticas.TipoGraficoEnum.CIRCULAR:
                        estadisticasResult.put(
                                config.getId(),
                                calcularGraficoCircular(dataAGraficar, config.getCampoAgrupacion(), limiteCategorias)
                        );
                        break;

                    case ConfiguracionEstadisticas.TipoGraficoEnum.BARRAS_APILADAS:
                        estadisticasResult.put(
                                config.getId(),
                                calcularGraficoBarrasApiladas(dataAGraficar, config.getCampoAgrupacion(), config.getCampoSerie())
                        );
                        break;

                    default:
                        log.warn("Tipo de gráfico no soportado: {}", config.getTipoGrafico());
                }
            }

            ApiResponseEstadisticas response = new ApiResponseEstadisticas();
            response.setStatus(200);
            response.setUserMessage("Ok");
            response.setData(estadisticasResult);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error calculando estadísticas agnósticas: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

//    private List<Map<String, Object>> calcularGraficoCircular(List<Map<String, Object>> data, String campo) {
//        // Agrupar y contar usando Streams
//        Map<String, Long> conteo = data.stream()
//                .filter(m -> m.get(campo) != null && !m.get(campo).toString().isBlank())
//                .collect(Collectors.groupingBy(m -> m.get(campo).toString().trim(), Collectors.counting()));
//
//        // Mapear al formato [{name: "Grupo", value: 10}]
//        return conteo.entrySet().stream()
//                .map(e -> {
//                    Map<String, Object> map = new HashMap<>();
//                    map.put("name", e.getKey());
//                    map.put("value", e.getValue());
//                    return map;
//                })
//                .sorted((a, b) -> Long.compare((Long) b.get("value"), (Long) a.get("value"))) // Ordenar descendente
//                .toList();
//    }
    private List<Map<String, Object>> calcularGraficoCircular(List<Map<String, Object>> data, String campo, Integer maxCategorias) {

        // Agrupar y contar usando Streams
        Map<String, Long> conteo = data.stream()
                .filter(m -> m.get(campo) != null && !m.get(campo).toString().isBlank())
                .collect(Collectors.groupingBy(m -> m.get(campo).toString().trim(), Collectors.counting()));

        List<Map<String, Object>> sorted = conteo.entrySet().stream()
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", e.getKey());
                    map.put("value", e.getValue());
                    return map;
                })
                .sorted((a, b) -> Long.compare((Long) b.get("value"), (Long) a.get("value")))
                .toList();

        // NUEVO: Lógica de Top N + "Otros"
        if (maxCategorias != null && maxCategorias > 0 && sorted.size() > maxCategorias) {
            List<Map<String, Object>> topN = new ArrayList<>(sorted.subList(0, maxCategorias - 1));

            long sumaOtros = sorted.subList(maxCategorias - 1, sorted.size()).stream()
                    .mapToLong(m -> (Long) m.get("value"))
                    .sum();

            Map<String, Object> otros = new HashMap<>();
            otros.put("name", "Otros (" + (sorted.size() - maxCategorias + 1) + ")");
            otros.put("value", sumaOtros);
            topN.add(otros);

            return topN;
        }

        return sorted;
    }

    private Map<String, Object> calcularGraficoBarrasApiladas(List<Map<String, Object>> data, String campoEjeX, String campoSerie) {

        // 1. Obtener valores únicos para Eje X (ej. Facultades) y Series (ej. Roles)
        List<String> categoriasX = data.stream()
                .map(m -> m.get(campoEjeX) != null ? m.get(campoEjeX).toString().trim() : "Sin asignar")
                .distinct().sorted().toList();

        List<String> nombresSeries = data.stream()
                .map(m -> m.get(campoSerie) != null ? m.get(campoSerie).toString().trim() : "Sin asignar")
                .distinct().sorted().toList();

        // 2. Agrupación Bidimensional rápida: Serie -> EjeX -> Conteo
        Map<String, Map<String, Long>> agrupacion = data.stream().collect(
                Collectors.groupingBy(
                        m -> m.get(campoSerie) != null ? m.get(campoSerie).toString().trim() : "Sin asignar",
                        Collectors.groupingBy(
                                m -> m.get(campoEjeX) != null ? m.get(campoEjeX).toString().trim() : "Sin asignar",
                                Collectors.counting()
                        )
                )
        );

        // 3. Formatear para ECharts
        List<Map<String, Object>> seriesEcharts = new ArrayList<>();

        for (String serie : nombresSeries) {
            Map<String, Object> configSerie = new HashMap<>();
            configSerie.put("name", serie);
            configSerie.put("type", "bar");
            configSerie.put("stack", "total"); // Activa el apilado

            // Construir el array de datos en el mismo orden que categoriasX
            List<Long> datosSerie = new ArrayList<>();
            Map<String, Long> datosX = agrupacion.getOrDefault(serie, Collections.emptyMap());

            for (String catX : categoriasX) {
                datosSerie.add(datosX.getOrDefault(catX, 0L)); // Si no hay datos, pone 0
            }

            configSerie.put("data", datosSerie);
            seriesEcharts.add(configSerie);
        }

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("ejeX", categoriasX);
        resultado.put("series", seriesEcharts);
        return resultado;
    }



    private List<Map<String, Object>> obtenerDataQuemada(String codigo) {
        // Futura rpta SQL to json
        String archivo = switch (codigo.toUpperCase()) {
            case "R01" -> "datos-reporte-r01.json";
            case "R02" -> "datos-reporte-r02.json";
            case "R03" -> "datos-reporte-r03.json";
            case "R04" -> "datos-reporte-r02.json";
            case "R07" -> "datos-reporte-r07.json";
            case "VIG" -> "vigencia-v1.json";

            default -> "aaa";
        };

        try (InputStream inputStream = new ClassPathResource(archivo).getInputStream()) {
            return mapper.readValue(
                    inputStream,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            throw new RuntimeException("Error leyendo archivo de datos quemados: " + archivo, e);
        }
    }

//    private List<Map<String, Object>> aplicarFiltros(
//            List<Map<String, Object>> data,
//            FiltrosReporteRequest request) {
//        if (request == null || request.getFiltros() == null || request.getFiltros().isEmpty()) {
//            return data;
//        }
//
//        Map<String, Object> filtros = request.getFiltros();
//
//        return data.stream()
//                .filter(row -> cumpleFiltros(row, filtros))
//                .toList();
//    }
private List<Map<String, Object>> aplicarFiltros(
        List<Map<String, Object>> data,
        FiltrosReporteRequest request,
        ReporteMetadata metadata) {

    if (request == null || request.getFiltros() == null || request.getFiltros().isEmpty()) {
        return data;
    }

    Map<String, Object> filtros = request.getFiltros();
    List<ConfiguracionFechas> configsFechas = metadata != null ? metadata.getConfiguracionFechas() : null;

    return data.stream().filter(fila -> {

        // 1. Evaluar configuraciones de fechas
        if (configsFechas != null && !configsFechas.isEmpty()) {
            for (ConfiguracionFechas config : configsFechas) {
                boolean cumpleFechas = evaluarFiltroFechas(fila, filtros, config);
                if (!cumpleFechas) {
                    return false;
                }
            }
        }

        // 2. Extraer solo los filtros de texto (quitando los de fechas que ya se evaluaron)
        Map<String, Object> filtrosTexto = new HashMap<>();
        for (Map.Entry<String, Object> entry : filtros.entrySet()) {
            boolean esFiltroFecha = false;
            if (configsFechas != null) {
                esFiltroFecha = configsFechas.stream().anyMatch(c ->
                        entry.getKey().equals(c.getFiltroDesde()) || entry.getKey().equals(c.getFiltroHasta())
                );
            }
            if (!esFiltroFecha) {
                filtrosTexto.put(entry.getKey(), entry.getValue());
            }
        }

        // 3. Delegar la evaluación de texto al método que tiene la Regex
        return cumpleFiltros(fila, filtrosTexto);

    }).toList();
}

//    private boolean evaluarFiltroFechas(Map<String, Object> fila, Map<String, Object> filtros, ConfiguracionFechas config) {
//        String strDesde = filtros.containsKey(config.getFiltroDesde()) ? String.valueOf(filtros.get(config.getFiltroDesde())) : null;
//        String strHasta = filtros.containsKey(config.getFiltroHasta()) ? String.valueOf(filtros.get(config.getFiltroHasta())) : null;
//
//        if ((strDesde == null || strDesde.isBlank() || "null".equals(strDesde)) &&
//                (strHasta == null || strHasta.isBlank() || "null".equals(strHasta))) {
//            return true;
//        }
//
//        LocalDate fechaUserDesde = (strDesde != null && !strDesde.isBlank() && !"null".equals(strDesde))
//                ? LocalDate.parse(strDesde) : LocalDate.MIN;
//        LocalDate fechaUserHasta = (strHasta != null && !strHasta.isBlank() && !"null".equals(strHasta))
//                ? LocalDate.parse(strHasta) : LocalDate.now();
//
//        if (config.getTipo() != null && "INTERVALO".equalsIgnoreCase(config.getTipo().name())) {
//            LocalDate dataInicio = extraerFecha(fila, config.getColumnaInicio(), LocalDate.MIN);
//            LocalDate dataFin = extraerFecha(fila, config.getColumnaFin(), LocalDate.now());
//            // Se cruzan si Inicio <= LímiteSuperior Y Fin >= LímiteInferior
//            return !dataInicio.isAfter(fechaUserHasta) && !dataFin.isBefore(fechaUserDesde);
//
//        } else if (config.getTipo() != null && "EVENTO".equalsIgnoreCase(config.getTipo().name())) {
//            // El EVENTO solo extrae una columna y evalúa si cae dentro de la ventana de tiempo
//            LocalDate dataFecha = extraerFecha(fila, config.getColumnaFecha(), null);
//            if (dataFecha == null) return false;
//
//            return !dataFecha.isBefore(fechaUserDesde) && !dataFecha.isAfter(fechaUserHasta);
//        }
//
//        return true;
//    }
private boolean evaluarFiltroFechas(Map<String, Object> fila, Map<String, Object> filtros, ConfiguracionFechas config) {
    String strDesde = filtros.containsKey(config.getFiltroDesde()) ? String.valueOf(filtros.get(config.getFiltroDesde())) : null;
    String strHasta = filtros.containsKey(config.getFiltroHasta()) ? String.valueOf(filtros.get(config.getFiltroHasta())) : null;

    if ((strDesde == null || strDesde.isBlank() || "null".equals(strDesde)) &&
            (strHasta == null || strHasta.isBlank() || "null".equals(strHasta))) {
        return true;
    }

    LocalDate fechaUserDesde = (strDesde != null && !strDesde.isBlank() && !"null".equals(strDesde))
            ? LocalDate.parse(strDesde) : LocalDate.MIN;
    LocalDate fechaUserHasta = (strHasta != null && !strHasta.isBlank() && !"null".equals(strHasta))
            ? LocalDate.parse(strHasta) : LocalDate.now();

    if (config.getTipo() != null && "INTERVALO".equalsIgnoreCase(config.getTipo().name())) {
        LocalDate dataInicio = extraerFecha(fila, config.getColumnaInicio(), LocalDate.MIN);
        LocalDate dataFin = extraerFecha(fila, config.getColumnaFin(), LocalDate.now());

        boolean cruzaFechas = !dataInicio.isAfter(fechaUserHasta) && !dataFin.isBefore(fechaUserDesde);

        // LOG DE DIAGNÓSTICO PARA INTERVALO
        log.info("Filtro INTERVALO -> UserDesde: {}, UserHasta: {} | FilaInicio ({}): {}, FilaFin ({}): {} | Resultado: {}",
                fechaUserDesde, fechaUserHasta, config.getColumnaInicio(), dataInicio, config.getColumnaFin(), dataFin, cruzaFechas);

        return cruzaFechas;

    } else if (config.getTipo() != null && "EVENTO".equalsIgnoreCase(config.getTipo().name())) {
        LocalDate dataFecha = extraerFecha(fila, config.getColumnaFecha(), null);
        if (dataFecha == null) return false;

        boolean estaEnRango = !dataFecha.isBefore(fechaUserDesde) && !dataFecha.isAfter(fechaUserHasta);

        // LOG DE DIAGNÓSTICO PARA EVENTO
        log.info("Filtro EVENTO -> UserDesde: {}, UserHasta: {} | FilaFecha ({}): {} | Resultado: {}",
                fechaUserDesde, fechaUserHasta, config.getColumnaFecha(), dataFecha, estaEnRango);

        return estaEnRango;
    }

    return true;
}

    private LocalDate extraerFecha(Map<String, Object> fila, String columna, LocalDate valorPorDefecto) {
        Object val = fila.get(columna);
        if (val == null || val.toString().isBlank()) {
            return valorPorDefecto;
        }
        try {
            String isoStr = val.toString().trim();
            if (isoStr.length() >= 10) {
                return LocalDate.parse(isoStr.substring(0, 10)); // Corta "2001-01-01T05:00..." a "2001-01-01"
            }
            return LocalDate.parse(isoStr);
        } catch (Exception e) {
            log.warn("Error parseando fecha para columna '{}' con valor '{}'. Se usará valorPorDefecto.", columna, val, e);
            return valorPorDefecto;
        }
    }

//    private boolean cumpleFiltros(Map<String, Object> row, Map<String, Object> filtros) {
//        for (Map.Entry<String, Object> filtro : filtros.entrySet()) {
//            String campo = filtro.getKey();
//            Object valorFiltro = filtro.getValue();
//
//            if (valorFiltro == null || valorFiltro.toString().isBlank()) {
//                continue;
//            }
//
//            Object valorFila = row.get(campo);
//
//            if (valorFila == null) {
//                return false;
//            }
//
//            String textoFila = valorFila.toString().toLowerCase();
//            String textoFiltro = valorFiltro.toString().toLowerCase();
//
//            if (!textoFila.contains(textoFiltro)) {
//                return false;
//            }
//        }
//
//        return true;
//    }
private boolean cumpleFiltros(Map<String, Object> row, Map<String, Object> filtros) {
    for (Map.Entry<String, Object> filtro : filtros.entrySet()) {
        String campo = filtro.getKey();
        Object valorFiltro = filtro.getValue();

        if (valorFiltro == null || valorFiltro.toString().isBlank()) {
            continue;
        }

        Object valorFila = row.get(campo);
        if (valorFila == null) {
            return false;
        }

        String textoFila = valorFila.toString().trim().toLowerCase();
        String textoFiltro = valorFiltro.toString().trim().toLowerCase();

        // 1. Priorizar coincidencia exacta (Resuelve el caso de ACTIVO == ACTIVO)
        if (textoFila.equals(textoFiltro)) {
            continue;
        }

//        // 2. Coincidencia por patrón (contains) para búsquedas parciales
//        if (textoFila.contains(textoFiltro)) {
//
//            // Heurística para evitar el falso positivo de estados anidados ("activo" dentro de "inactivo")
//            // Si el valor de la fila es una sola palabra puramente alfabética y el filtro actúa como un sufijo,
//            // se asume que es una palabra diferente y se descarta el match.
//            if (textoFila.matches("[a-záéíóúñ]+") && textoFila.endsWith(textoFiltro)) {
//                return false;
//            }
//
//            continue; // Cumple el filtro por patrón (ej. "105" dentro de "1061749604")
//        }
        String regex = ".*\\b" + java.util.regex.Pattern.quote(textoFiltro) + "\\b.*";
        if (textoFila.matches(regex)) {
            continue;
        }

        // 3. Fallback: Contains tradicional (solo para textos muy parciales)
        if (textoFila.contains(textoFiltro)) {
            // Si el texto de la fila es "inactivo" y el filtro es "activo",
            // el contains es true, pero NO queremos que pase.
            if (textoFila.endsWith("inactivo") && textoFiltro.equals("activo")) {
                return false;
            }
            continue;
        }

        // Si no es coincidencia exacta ni contiene el patrón válido, falla
        return false;
    }

    return true;
}

}