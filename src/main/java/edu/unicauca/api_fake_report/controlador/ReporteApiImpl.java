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
import java.nio.charset.StandardCharsets;
import java.util.*;
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

        List<Map<String, Object>> dataFiltrada = aplicarFiltros(
                dataCompleta,
                filtrosReporteRequest);

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

    @Override
    public ResponseEntity<Resource> generarReporte(
            String codigo,
            String formato,
            FiltrosReporteRequest request) { // Renombrado a 'request' por legibilidad

        try {
            
            List<Map<String, Object>> dataFuente;

            // 1. DETERMINAR LA FUENTE DE DATOS:
            // Si el request contiene 'data', esa es nuestra fuente. Si no, usamos la data quemada.
            if (request != null && request.getData() != null && !request.getData().isEmpty()) {
                dataFuente = request.getData();
            } else {
                dataFuente = obtenerDataQuemada(codigo);
            }

            // 2. APLICAR FILTROS A LA FUENTE DE DATOS:
            List<Map<String, Object>> dataFinal = aplicarFiltros(dataFuente, request);

            // 3. PREPARAR PETICIÓN AL MICROSERVICIO:
            ReporteExternoRequest requestExterno = new ReporteExternoRequest();
            requestExterno.setTipoReporte(codigo);
            requestExterno.setFormato(formato);
            requestExterno.setData(dataFinal); // Pasamos la data final (obtenida del body y filtrada)

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            log.info("REQUEST: {}", requestExterno);

            HttpEntity<ReporteExternoRequest> entity = new HttpEntity<>(requestExterno, headers);

            // 4. EJECUTAR REST TEMPLATE:
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
                        : MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
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

    private List<Map<String, Object>> obtenerDataQuemada(String codigo) {
        // Futura rpta SQL to json
        String archivo = switch (codigo.toUpperCase()) {
            case "R01" -> "datos-reporte-r01.json";
            case "R02" -> "datos-reporte-r02.json";
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

    private List<Map<String, Object>> aplicarFiltros(
            List<Map<String, Object>> data,
            FiltrosReporteRequest request) {
        if (request == null || request.getFiltros() == null || request.getFiltros().isEmpty()) {
            return data;
        }

        Map<String, Object> filtros = request.getFiltros();

        return data.stream()
                .filter(row -> cumpleFiltros(row, filtros))
                .toList();
    }

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

            String textoFila = valorFila.toString().toLowerCase();
            String textoFiltro = valorFiltro.toString().toLowerCase();

            if (!textoFila.contains(textoFiltro)) {
                return false;
            }
        }

        return true;
    }

}