
_all: docker

docker:
	docker build . -t kjarosh/ms-graph-simulator:latest

push: docker
	docker push kjarosh/ms-graph-simulator:latest

.PHONY: _all docker push
