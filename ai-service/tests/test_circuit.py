import pytest

from app.circuit import ModelCircuit
from app.errors import ModelCircuitOpenError


def test_failure_opens_circuit_and_only_one_recovery_probe_is_allowed() -> None:
    clock = [100.0]
    circuit = ModelCircuit(2, 10, now=lambda: clock[0])
    circuit.failure(circuit.enter())
    circuit.failure(circuit.enter())
    with pytest.raises(ModelCircuitOpenError):
        circuit.enter()
    clock[0] = 111.0
    probe = circuit.enter()
    with pytest.raises(ModelCircuitOpenError):
        circuit.enter()
    circuit.success(probe)
    circuit.enter()


def test_late_success_from_before_open_does_not_bypass_cooldown() -> None:
    circuit = ModelCircuit(1, 10)
    old = circuit.enter()
    circuit.failure(old)
    circuit.success(old)
    with pytest.raises(ModelCircuitOpenError):
        circuit.enter()


def test_cancelled_recovery_probe_can_be_replaced() -> None:
    clock = [100.0]
    circuit = ModelCircuit(1, 10, now=lambda: clock[0])
    circuit.failure(circuit.enter())
    clock[0] = 111.0
    probe = circuit.enter()
    circuit.abandon(probe)
    circuit.enter()
