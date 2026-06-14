package edu.unicauca.api_fake_report.controlador;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class ReporteExternoRequest {

    private String tipoReporte;
    private String formato;
    private List<Map<String, Object>> data;

    public String getTipoReporte() {
        return tipoReporte;
    }

    public void setTipoReporte(String tipoReporte) {
        this.tipoReporte = tipoReporte;
    }

    public String getFormato() {
        return formato;
    }

    public void setFormato(String formato) {
        this.formato = formato;
    }

    public List<Map<String, Object>> getData() {
        return data;
    }

    public void setData(List<Map<String, Object>> data) {
        this.data = data;
    }
}