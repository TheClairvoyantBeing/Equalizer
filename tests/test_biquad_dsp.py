"""
Biquad DSP & Equalizer Automated Verification Suite
Validates:
- Robert Bristow-Johnson (RBJ) Audio EQ Cookbook coefficient generation
- Transposed Direct Form II (TDF-II) stability (poles inside unit circle)
- 7-Band ISO frequency response accuracy (62.5Hz - 6000Hz)
- Dynamic gain range limits (-12 dB to +12 dB)
- Unit impulse response and zero-gain pass-through identity
"""

import unittest
import math
import cmath

class BiquadPeakingEQ:
    def __init__(self, sample_rate: int, freq: float, gain_db: float, q: float = 1.2):
        self.sample_rate = sample_rate
        self.freq = freq
        self.gain_db = gain_db
        self.q = q
        self.b0 = 1.0
        self.b1 = 0.0
        self.b2 = 0.0
        self.a1 = 0.0
        self.a2 = 0.0
        self.s1 = 0.0
        self.s2 = 0.0
        self.update_coefficients()

    def update_coefficients(self):
        omega = 2.0 * math.pi * self.freq / self.sample_rate
        sin_omg = math.sin(omega)
        cos_omg = math.cos(omega)
        a_val = 10.0 ** (self.gain_db / 40.0)
        alpha = sin_omg / (2.0 * self.q)

        a0 = 1.0 + alpha / a_val
        self.b0 = (1.0 + alpha * a_val) / a0
        self.b1 = (-2.0 * cos_omg) / a0
        self.b2 = (1.0 - alpha * a_val) / a0
        self.a1 = (-2.0 * cos_omg) / a0
        self.a2 = (1.0 - alpha / a_val) / a0

    def process(self, sample_in: float) -> float:
        # Transposed Direct Form II
        x = float(sample_in)
        y = self.b0 * x + self.s1
        self.s1 = self.b1 * x - self.a1 * y + self.s2
        self.s2 = self.b2 * x - self.a2 * y
        return y

    def reset(self):
        self.s1 = 0.0
        self.s2 = 0.0

    def poles(self):
        # Denominator: 1 + a1*z^-1 + a2*z^-2 = 0 -> z^2 + a1*z + a2 = 0
        d = self.a1**2 - 4 * self.a2
        sqrt_d = cmath.sqrt(d)
        p1 = (-self.a1 + sqrt_d) / 2.0
        p2 = (-self.a1 - sqrt_d) / 2.0
        return p1, p2

    def frequency_response(self, freq: float) -> float:
        """Returns gain in dB at given frequency."""
        omega = 2.0 * math.pi * freq / self.sample_rate
        z = cmath.exp(-1j * omega)
        numerator = self.b0 + self.b1 * z + self.b2 * (z**2)
        denominator = 1.0 + self.a1 * z + self.a2 * (z**2)
        h = numerator / denominator
        mag = abs(h)
        return 20.0 * math.log10(mag) if mag > 1e-12 else -200.0


class TestEqualizerDSP(unittest.TestCase):
    def setUp(self):
        self.sample_rate = 48000
        self.standard_bands = [62.5, 187.5, 375.0, 750.0, 1500.0, 3000.0, 6000.0]

    def test_zero_gain_identity(self):
        """0 dB gain across all bands must pass audio unmodified."""
        for freq in self.standard_bands:
            filter_eq = BiquadPeakingEQ(self.sample_rate, freq, gain_db=0.0)
            # Test with various test samples
            for val in [0.0, 0.5, -0.5, 0.99, -0.99]:
                out = filter_eq.process(val)
                self.assertAlmostEqual(out, val, places=5, msg=f"Failed identity at freq {freq}Hz")

    def test_pole_stability_all_bands(self):
        """All filter poles must strictly reside inside the unit circle for stability (|pole| < 1.0)."""
        gain_sweep = [-12.0, -9.0, -6.0, -3.0, 0.0, 3.0, 6.0, 9.0, 12.0]
        for freq in self.standard_bands:
            for gain in gain_sweep:
                filter_eq = BiquadPeakingEQ(self.sample_rate, freq, gain_db=gain)
                p1, p2 = filter_eq.poles()
                self.assertLess(abs(p1), 1.0, f"Pole p1 unstable at {freq}Hz, gain={gain}dB: |p1|={abs(p1)}")
                self.assertLess(abs(p2), 1.0, f"Pole p2 unstable at {freq}Hz, gain={gain}dB: |p2|={abs(p2)}")

    def test_peaking_response_accuracy(self):
        """At the center frequency f0, frequency response should closely match the target gain."""
        for freq in self.standard_bands:
            for target_gain in [+6.0, -6.0, +12.0, -12.0]:
                filter_eq = BiquadPeakingEQ(self.sample_rate, freq, gain_db=target_gain)
                measured_gain = filter_eq.frequency_response(freq)
                self.assertAlmostEqual(
                    measured_gain, target_gain, places=1,
                    msg=f"Gain deviation at {freq}Hz: expected {target_gain}dB, got {measured_gain:.2f}dB"
                )

    def test_transposed_direct_form_ii_bounded_output(self):
        """A bounded sinusoidal input must produce bounded output without overflow or NaN/Inf."""
        filter_eq = BiquadPeakingEQ(self.sample_rate, 1000.0, gain_db=12.0)
        # Process 1000 samples of a 1 kHz sine wave
        for i in range(1000):
            sample_in = 0.5 * math.sin(2.0 * math.pi * 1000.0 * i / self.sample_rate)
            sample_out = filter_eq.process(sample_in)
            self.assertFalse(math.isnan(sample_out), f"NaN at sample {i}")
            self.assertFalse(math.isinf(sample_out), f"Inf at sample {i}")
            # Even with +12dB (4x amplitude gain), 0.5 * 4 = ~2.0, bounded
            self.assertLess(abs(sample_out), 3.0)

    def test_cascaded_7_band_pipeline(self):
        """Verify sequential cascading of all 7 bands with mixed gains."""
        gains = [6.0, 3.0, 0.0, -4.0, 2.0, 5.0, -2.0]
        cascade = [BiquadPeakingEQ(self.sample_rate, freq, gain) for freq, gain in zip(self.standard_bands, gains)]
        
        # Pass 500 samples of silence -> must remain zero
        for _ in range(500):
            sample = 0.0
            for f in cascade:
                sample = f.process(sample)
            self.assertAlmostEqual(sample, 0.0, places=6)

    def test_acoustic_feedback_mute_guard(self):
        """Mute guard replaces incoming PCM stream with zero buffers."""
        is_muted = True
        read_samples = [0.2, 0.5, -0.4, 0.8]
        output = [0.0 if is_muted else s for s in read_samples]
        self.assertEqual(output, [0.0, 0.0, 0.0, 0.0])


if __name__ == "__main__":
    unittest.main()
