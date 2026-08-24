import random
from rsk_trainer.noise import RussianKeyboardNoise

def test_noise_changes_normal_word():
    n=RussianKeyboardNoise(random.Random(1))
    assert n.corrupt_word("сентябрь").text != "сентябрь"
