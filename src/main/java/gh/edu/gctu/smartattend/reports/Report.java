package gh.edu.gctu.smartattend.reports;

import java.util.List;

public record Report(String title, List<String> meta, List<String> headers, List<List<Object>> rows, String fileBase) {}
