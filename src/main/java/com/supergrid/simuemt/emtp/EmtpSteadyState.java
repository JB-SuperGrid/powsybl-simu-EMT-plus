/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The steady-state solution an EMTP time-domain run starts from ({@code <name>ss.xml}): at the network's frequency, each node's
 * voltage as a phasor - its crest in volts and its angle in degrees, {@code v(t) = m cos(wt + p)}, as the run's first samples show
 * it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class EmtpSteadyState {

    /** A phasor: its crest and its angle in degrees. */
    public record Phasor(double magnitude, double angleDegrees) {
    }

    private final double frequencyHz;
    private final Map<String, Phasor> voltages;

    private EmtpSteadyState(double frequencyHz, Map<String, Phasor> voltages) {
        this.frequencyHz = frequencyHz;
        this.voltages = Map.copyOf(voltages);
    }

    /** The steady-state solution of netlist {@code name}, in its project folder {@code projectDirectory}. */
    public static EmtpSteadyState read(Path projectDirectory, String name) {
        Path file = projectDirectory.resolve(name + "ss.xml");
        if (!Files.exists(file)) {
            throw new PowsyblException("SimuEmt Plus: no steady-state solution " + file + " - the run started from rest?");
        }
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            document = factory.newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new PowsyblException("SimuEmt Plus: could not read " + file, e);
        }
        NodeList frequencies = document.getElementsByTagName("Frequency");
        if (frequencies.getLength() != 1) {
            throw new PowsyblException("SimuEmt Plus: " + file + " holds " + frequencies.getLength() + " frequencies, not one");
        }
        Element frequency = (Element) frequencies.item(0);
        Map<String, Phasor> voltages = new LinkedHashMap<>();
        NodeList nodes = frequency.getElementsByTagName("S");
        for (int n = 0; n < nodes.getLength(); n++) {
            Element node = (Element) nodes.item(n);
            voltages.put(node.getAttribute("N"), new Phasor(Double.parseDouble(node.getAttribute("m")), Double.parseDouble(node.getAttribute("p"))));
        }
        return new EmtpSteadyState(Double.parseDouble(frequency.getAttribute("f")), voltages);
    }

    public double frequencyHz() {
        return frequencyHz;
    }

    /** Node {@code name}'s voltage, if the solution has it. */
    public Optional<Phasor> voltage(String name) {
        return Optional.ofNullable(voltages.get(name));
    }

    /** Every node's voltage, by name. */
    public Map<String, Phasor> voltages() {
        return voltages;
    }
}
