SIZE = 1024
def generate_file(size_mb, filename=f"sample-{SIZE}KB.txt"):
    target_size = size_mb * 1024
    with open(filename, "wb") as f:
        f.write(b"A" * target_size)

generate_file(SIZE)